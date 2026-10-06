<script setup lang="ts">
import { computed } from 'vue'
import { getDocumentStatusDisplay } from '@/utils/documentStatus'

const props = defineProps<{
  status: string
  errorMsg?: string
}>()

/** 批次 07 · 任务 7.0.5：状态映射与 tooltip 说明统一由 utils 提供（可单测） */
const display = computed(() => getDocumentStatusDisplay(props.status))

/** FAILED 且有错误信息时，tooltip 优先展示具体错误 */
const tooltip = computed(() =>
  props.status === 'FAILED' && props.errorMsg ? props.errorMsg : display.value.hint
)
</script>

<template>
  <el-tooltip :content="tooltip" placement="top">
    <el-tag :type="display.type || 'info'">
      <el-icon v-if="display.loading" class="is-loading">
        <Loading />
      </el-icon>
      {{ display.label }}
    </el-tag>
  </el-tooltip>
</template>

<style scoped lang="scss">
.is-loading {
  margin-right: 4px;
}
</style>
