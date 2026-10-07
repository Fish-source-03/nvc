<template>
  <div class="rules-manager">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>{{ $t('quality.ruleManager') }}</span>
          <el-button type="primary" @click="handleAdd">
            <el-icon><Plus /></el-icon>
            {{ $t('quality.addRule') }}
          </el-button>
        </div>
      </template>

      <el-table :data="rules" stripe v-loading="loading">
        <el-table-column prop="ruleName" :label="$t('quality.ruleName')" min-width="150" />
        <el-table-column prop="ruleType" :label="$t('quality.ruleType')" width="120">
          <template #default="{ row }">
            <el-tag>{{ ruleTypeLabel(row.ruleType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="$t('quality.ruleParam')" min-width="200">
          <template #default="{ row }">
            {{ ruleParamText(row) }}
          </template>
        </el-table-column>
        <el-table-column :label="$t('quality.ruleStatus')" width="100" align="center">
          <template #default="{ row }">
            <el-switch
              v-model="row.enabled"
              @change="toggleRule(row)"
              size="small"
            />
          </template>
        </el-table-column>
        <el-table-column :label="$t('common.operation')" width="150" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleEdit(row)">
              {{ $t('common.edit') }}
            </el-button>
            <el-button link type="danger" @click="handleDelete(row)">
              {{ $t('common.delete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && rules.length === 0" :description="$t('common.noData')" />
    </el-card>

    <RuleEditor
      :visible="editorVisible"
      :rule="editingRule"
      @update:visible="editorVisible = $event"
      @save="handleSave"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import RuleEditor from '@/components/quality/RuleEditor.vue'
import { dataqualityApi, fromRuleDto, toRuleDto } from '@/api/dataquality'
import type { QualityRule } from '@/api/dataquality'

/**
 * 质检规则管理页（批次 10 · 任务 10.1，问题 35）。
 * <p>
 * 修复前：规则完全存在浏览器 localStorage（含 5 条硬编码默认规则），
 * 增删改只写回本地，<b>不参与任何真实质检</b>。
 * 现在：数据源切到后端 {@code /api/dataquality/rules}，规则落库后由
 * {@code DataQualityChecker} 动态加载并参与真实质检（下次质检即生效）。
 * 页面结构与交互（表格 / 开关 / 弹窗编辑器）保持不变。
 * </p>
 */
const { t } = useI18n()

const rules = ref<QualityRule[]>([])
const loading = ref(false)
const editorVisible = ref(false)
const editingRule = ref<QualityRule | null>(null)

function ruleTypeLabel(type: string): string {
  const map: Record<string, string> = {
    completeness: t('quality.ruleTypes.completeness'),
    uniqueness: t('quality.ruleTypes.uniqueness'),
    format: t('quality.ruleTypes.format'),
    encoding: t('quality.ruleTypes.encoding'),
    length: t('quality.ruleTypes.length')
  }
  return map[type] || type
}

/** 规则参数列的展示文本（按类型展示真正生效的参数） */
function ruleParamText(row: QualityRule): string {
  switch (row.ruleType) {
    case 'completeness':
      return row.targetFields || '—'
    case 'format':
      return row.pattern || '—'
    case 'encoding':
      return row.encodingCharset || 'UTF-8'
    case 'length':
      return `${row.minLength ?? 0}-${row.maxLength ?? '∞'}`
    default:
      return '—'
  }
}

/** 从后端加载规则（不再使用 localStorage） */
async function loadRules() {
  loading.value = true
  try {
    const res = await dataqualityApi.listRules()
    rules.value = (res.data || []).map(fromRuleDto)
  } catch {
    // 错误提示由 axios 拦截器统一处理
    rules.value = []
  } finally {
    loading.value = false
  }
}

function handleAdd() {
  editingRule.value = null
  editorVisible.value = true
}

function handleEdit(rule: QualityRule) {
  editingRule.value = { ...rule }
  editorVisible.value = true
}

async function handleSave(rule: QualityRule) {
  const dto = toRuleDto(rule)
  try {
    if (rule.id) {
      await dataqualityApi.updateRule(rule.id, dto)
    } else {
      await dataqualityApi.createRule(dto)
    }
    ElMessage.success(t('common.success'))
    editorVisible.value = false
    await loadRules()
  } catch {
    // 失败提示由拦截器统一处理（如未知规则类型、非法正则）
  }
}

function handleDelete(rule: QualityRule) {
  ElMessageBox.confirm(
    t('quality.deleteRule') + '?',
    t('common.tips'),
    { confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel'), type: 'warning' }
  ).then(async () => {
    if (!rule.id) return
    try {
      await dataqualityApi.deleteRule(rule.id)
      ElMessage.success(t('common.success'))
      await loadRules()
    } catch {
      // 失败提示由拦截器统一处理
    }
  }).catch(() => {})
}

async function toggleRule(rule: QualityRule) {
  if (!rule.id) return
  try {
    await dataqualityApi.setRuleEnabled(rule.id, rule.enabled)
    ElMessage.success(t('common.success'))
  } catch {
    // 失败时重新加载，恢复开关的真实状态
    await loadRules()
  }
}

onMounted(loadRules)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
