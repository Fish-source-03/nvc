<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { pickDefaultDomain } from '@/stores/auth'

const props = defineProps<{
  loading: boolean
  disabled: boolean
  domains?: { value: string; label: string }[]
}>()

const emit = defineEmits<{
  send: [content: string, domain?: string]
  stop: []
}>()

const inputValue = ref('')

/**
 * ★ 批次 03 联动修复：默认选中首个可用域，不再允许「全部域」（空值）。
 *
 * 后端 `/api/chat/ask` 与 `/api/chat/ask/stream` 已强制校验 domain：
 * 缺失 → 业务码 400（HTTP 200），越域 → 403。而 SSE 走 fetchEventSource，
 * 只看 HTTP 状态，空域导致的 400 在界面上表现为"无响应"。
 */
const selectedDomain = ref('')

/** 调用方是否提供了域列表 —— 提供即意味着"必须选一个非空域" */
const requiresDomain = computed(() => props.domains !== undefined)

/** 是否存在可用业务域 */
const hasAvailableDomain = computed(() => (props.domains?.length ?? 0) > 0)

/** 调用方提供了域列表但用户一个可用域都没有 → 禁止输入/发送 */
const blockedByDomain = computed(() => requiresDomain.value && !hasAvailableDomain.value)

// 域列表异步就绪（用户信息加载）或切换用户时，保证选中值始终有效：
// 默认选中首个可用域（复用已单测的 pickDefaultDomain），列表为空则清空。
watch(
  () => props.domains,
  (list) => {
    const values = (list ?? []).map((d) => d.value)
    const fallback = pickDefaultDomain(values)
    if (fallback === null) {
      // 无任何可用域 → 清空，由 blockedByDomain 禁用输入并给出提示
      selectedDomain.value = ''
      return
    }
    // 当前选中项已失效（如切换账号）时才回退到默认域，避免覆盖用户选择
    if (!values.includes(selectedDomain.value)) {
      selectedDomain.value = fallback
    }
  },
  { immediate: true, deep: true },
)

function handleSend() {
  const content = inputValue.value.trim()
  if (!content || props.loading || props.disabled) return
  // 域缺失时不发出必然失败的请求，改为明确提示（替代静默失败）
  if (blockedByDomain.value) {
    ElMessage.warning('当前账号未授权任何业务域，无法提问，请联系管理员')
    return
  }
  if (requiresDomain.value && !selectedDomain.value) {
    ElMessage.warning('请先选择业务域')
    return
  }
  emit('send', content, selectedDomain.value || undefined)
  inputValue.value = ''
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    handleSend()
  }
}
</script>

<template>
  <div class="chat-input">
    <el-select
      v-if="domains && domains.length > 0"
      v-model="selectedDomain"
      placeholder="请选择业务域"
      class="chat-input__domain-select"
      :disabled="loading"
    >
      <!-- ★ 批次 03：不再提供「全部域」（空值）选项，后端已强制要求非空 domain -->
      <el-option
        v-for="d in domains"
        :key="d.value"
        :value="d.value"
        :label="d.label"
      />
    </el-select>
    <!-- ★ 批次 03：无可用域时的明确提示（替代点击后无响应的静默失败） -->
    <div v-else-if="blockedByDomain" class="chat-input__no-domain" role="alert">
      当前账号未授权任何业务域，无法提问，请联系管理员
    </div>
    <el-input
      v-model="inputValue"
      type="textarea"
      :rows="3"
      :disabled="loading || disabled || blockedByDomain"
      placeholder="请输入您的问题... (Enter 发送，Shift+Enter 换行)"
      resize="none"
      class="chat-input__textarea"
      @keydown="handleKeydown"
    />
    <el-button
      v-if="!loading"
      type="primary"
      :disabled="!inputValue.trim() || disabled || blockedByDomain"
      class="chat-input__btn"
      @click="handleSend"
    >
      发送
    </el-button>
    <el-button
      v-else
      type="danger"
      class="btn-stop-generate"
      @click="emit('stop')"
    >
      停止
    </el-button>
  </div>
</template>

<style scoped lang="scss">
.chat-input {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 12px 16px;
  background: #fff;
  border-top: 1px solid $border-color-light;

  &__domain-select {
    width: 140px;
    flex-shrink: 0;
    margin-bottom: 2px;
  }

  &__no-domain {
    flex-shrink: 0;
    max-width: 220px;
    margin-bottom: 2px;
    padding: 6px 10px;
    font-size: 12px;
    line-height: 1.4;
    color: $danger-color;
    background: var(--brand-danger-light, #FFEDED);
    border: 1px solid $danger-color;
    border-radius: 4px;
  }

  &__textarea {
    flex: 1;
  }

  &__btn {
    flex-shrink: 0;
    height: 40px;
    margin-bottom: 2px;
  }
}

.btn-stop-generate {
  flex-shrink: 0;
  height: 40px;
  margin-bottom: 2px;
}
</style>
