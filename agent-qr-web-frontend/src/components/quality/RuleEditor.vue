<template>
  <el-dialog
    :model-value="visible"
    :title="isEdit ? $t('quality.editRule') : $t('quality.addRule')"
    width="600px"
    @update:model-value="$emit('update:visible', $event)"
    @close="resetForm"
  >
    <el-form :model="form" :rules="rules" ref="formRef" label-width="100px">
      <el-form-item :label="$t('quality.ruleName')" prop="ruleName">
        <el-input v-model="form.ruleName" :placeholder="$t('quality.ruleName')" />
      </el-form-item>

      <el-form-item :label="$t('quality.ruleType')" prop="ruleType">
        <el-select v-model="form.ruleType" style="width: 100%">
          <el-option :label="$t('quality.ruleTypes.completeness')" value="completeness" />
          <el-option :label="$t('quality.ruleTypes.uniqueness')" value="uniqueness" />
          <el-option :label="$t('quality.ruleTypes.format')" value="format" />
          <el-option :label="$t('quality.ruleTypes.encoding')" value="encoding" />
          <el-option :label="$t('quality.ruleTypes.length')" value="length" />
        </el-select>
      </el-form-item>

      <!-- 完整性 / 格式 / 长度：目标字段（后端按该列表判定，逗号分隔） -->
      <el-form-item
        v-if="form.ruleType === 'completeness' || form.ruleType === 'format' || form.ruleType === 'length'"
        :label="$t('quality.targetFields')"
        prop="targetFields"
      >
        <el-input v-model="form.targetFields" placeholder="例如: email,name（逗号分隔）" />
      </el-form-item>

      <!-- 格式：正则表达式（可选，留空则走后端内置的日期/数字/百分比启发式检查） -->
      <el-form-item v-if="form.ruleType === 'format'" :label="$t('quality.ruleParam')" prop="pattern">
        <el-input v-model="form.pattern" placeholder="例如: ^[A-Za-z0-9]+$" />
      </el-form-item>

      <!-- 编码：期望字符集 -->
      <el-form-item v-if="form.ruleType === 'encoding'" :label="$t('quality.ruleParam')" prop="encodingCharset">
        <el-select v-model="form.encodingCharset" style="width: 100%">
          <el-option label="UTF-8" value="UTF-8" />
          <el-option label="GBK" value="GBK" />
          <el-option label="GB2312" value="GB2312" />
          <el-option label="ISO-8859-1" value="ISO-8859-1" />
        </el-select>
      </el-form-item>

      <!-- 长度：最小/最大 -->
      <el-form-item v-if="form.ruleType === 'length'" :label="$t('quality.ruleParam')" prop="maxLength">
        <el-row :gutter="10">
          <el-col :span="11">
            <el-input-number v-model="form.minLength" :min="0" placeholder="最小长度" style="width: 100%" />
          </el-col>
          <el-col :span="2" style="text-align: center; line-height: 32px">-</el-col>
          <el-col :span="11">
            <el-input-number v-model="form.maxLength" :min="0" placeholder="最大长度" style="width: 100%" />
          </el-col>
        </el-row>
      </el-form-item>

      <el-form-item :label="$t('quality.ruleStatus')" prop="enabled">
        <el-switch v-model="form.enabled" />
      </el-form-item>

      <!-- 实时预览 -->
      <el-divider>{{ $t('quality.ruleEditor.preview') }}</el-divider>
      <el-form-item :label="$t('quality.ruleEditor.sampleData')">
        <el-input v-model="sampleInput" placeholder="输入样例数据测试规则" @input="testRule" />
      </el-form-item>
      <el-form-item v-if="sampleInput" :label="$t('quality.ruleEditor.matchResult')">
        <el-tag :type="matchResult === null ? 'info' : matchResult ? 'success' : 'danger'">
          {{ matchResult === null ? '—' : matchResult ? $t('quality.ruleEditor.matched') : $t('quality.ruleEditor.notMatched') }}
        </el-tag>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="$emit('update:visible', false)">{{ $t('common.cancel') }}</el-button>
      <el-button type="primary" @click="handleSave">{{ $t('common.save') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue'
import type { FormInstance } from 'element-plus'
import type { QualityRule } from '@/api/dataquality'

/**
 * 质检规则编辑弹窗（批次 10 · 任务 10.1，问题 35）。
 * <p>
 * 页面结构与交互保持不变（弹窗 + 分类型的参数表单 + 实时预览），
 * 但参数表单改为<b>后端真正消费的参数</b>：
 * </p>
 * <ul>
 *   <li>完整性：目标字段（字段非空判定）；</li>
 *   <li>唯一性：无参数（重复检测由后端规则实现负责）；</li>
 *   <li>格式：目标字段 + 正则（可选）；</li>
 *   <li>编码：期望字符集；</li>
 *   <li>长度：目标字段 + 最小/最大长度。</li>
 * </ul>
 * 由此移除了原型中"阈值（合格率 ≥ x%）"这类后端并不消费的参数，
 * 避免"界面能配、后端不读"的假参数。
 */
const props = defineProps<{
  visible: boolean
  rule?: QualityRule | null
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  save: [rule: QualityRule]
}>()

const isEdit = ref(false)
const formRef = ref<FormInstance>()
const sampleInput = ref('')
const matchResult = ref<boolean | null>(null)

const form = reactive<QualityRule>({
  ruleName: '',
  ruleType: 'completeness',
  targetFields: '',
  pattern: '',
  encodingCharset: 'UTF-8',
  minLength: undefined,
  maxLength: undefined,
  enabled: true
})

/** 校验规则随类型变化（长度规则必须有目标字段与至少一个阈值；区间需自洽） */
const rules = computed(() => {
  const base: Record<string, any[]> = {
    ruleName: [{ required: true, message: '请输入规则名称', trigger: 'blur' }],
    ruleType: [{ required: true, message: '请选择规则类型', trigger: 'change' }]
  }
  if (form.ruleType === 'length') {
    base.targetFields = [{ required: true, message: '请输入目标字段', trigger: 'blur' }]
    base.maxLength = [
      {
        validator: (_rule: unknown, _value: unknown, callback: (error?: Error) => void) => {
          if (form.minLength === undefined && form.maxLength === undefined) {
            callback(new Error('请至少设置最小长度或最大长度'))
            return
          }
          if (
            form.minLength !== undefined && form.maxLength !== undefined &&
            form.minLength > form.maxLength
          ) {
            callback(new Error('最小长度不能大于最大长度'))
            return
          }
          callback()
        },
        trigger: 'blur'
      }
    ]
  }
  return base
})

watch(() => props.visible, (val) => {
  if (val) {
    if (props.rule) {
      isEdit.value = true
      Object.assign(form, props.rule)
    } else {
      isEdit.value = false
      resetForm()
    }
  }
})

function testRule() {
  if (!sampleInput.value) {
    matchResult.value = null
    return
  }
  switch (form.ruleType) {
    case 'completeness':
      matchResult.value = sampleInput.value.trim().length > 0
      break
    case 'uniqueness':
      matchResult.value = true
      break
    case 'format':
      if (form.pattern) {
        try {
          matchResult.value = new RegExp(form.pattern).test(sampleInput.value)
        } catch {
          matchResult.value = false
        }
      } else {
        // 未配置正则：后端走内置启发式检查，前端不做判断
        matchResult.value = null
      }
      break
    case 'encoding':
      matchResult.value = true
      break
    case 'length':
      const len = sampleInput.value.length
      matchResult.value = len >= (form.minLength ?? 0) && len <= (form.maxLength ?? Infinity)
      break
  }
}

function handleSave() {
  formRef.value?.validate((valid) => {
    if (valid) {
      emit('save', { ...form })
      emit('update:visible', false)
    }
  })
}

function resetForm() {
  form.ruleName = ''
  form.ruleType = 'completeness'
  form.targetFields = ''
  form.pattern = ''
  form.encodingCharset = 'UTF-8'
  form.minLength = undefined
  form.maxLength = undefined
  form.enabled = true
  form.id = undefined
  form.priority = undefined
  sampleInput.value = ''
  matchResult.value = null
  isEdit.value = false
}
</script>
