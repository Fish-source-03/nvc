<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { knowledgeApi } from '@/api/knowledge'
import type { DocumentInfo } from '@/types'
import { SENSITIVITY_LEVELS } from '@/types'
import { PROCESSING_STATUSES } from '@/utils/documentStatus'
import { useAuthStore } from '@/stores/auth'
import UploadDialog from '@/components/knowledge/UploadDialog.vue'
import DocumentTable from '@/components/knowledge/DocumentTable.vue'
import Pagination from '@/components/common/Pagination.vue'

const { t } = useI18n()

const authStore = useAuthStore()
const allowedDomains = computed(() => authStore.user?.allowedDomains ?? [])
const clearanceLevel = computed(() => authStore.user?.clearanceLevel ?? 0)

const documents = ref<DocumentInfo[]>([])
const loading = ref(false)
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)
const uploadDialogVisible = ref(false)

/** 筛选 */
const filterDomain = ref('')
const filterSensitivityLevel = ref<number>(-1)

/** 当前用户可见的密级选项 */
const filteredSensitivityLevels = computed(() =>
  SENSITIVITY_LEVELS.filter(s => s.value <= clearanceLevel.value)
)

let pollTimer: ReturnType<typeof setTimeout> | null = null

async function fetchDocuments() {
  loading.value = true
  try {
    const params: any = { page: currentPage.value, size: pageSize.value }
    if (filterDomain.value) params.domain = filterDomain.value
    if (filterSensitivityLevel.value !== -1) params.sensitivityLevel = filterSensitivityLevel.value
    const res = await knowledgeApi.listDocuments(params)
    documents.value = res.data.records
    total.value = res.data.total
  } catch {
    // 错误已在拦截器中统一处理
  } finally {
    loading.value = false
  }
}

/** 检查是否存在处理中的文档，有则启动轮询 */
function schedulePollIfNeeded() {
  const hasProcessing = documents.value.some(d => PROCESSING_STATUSES.has(d.status))
  if (hasProcessing) {
    pollTimer = setTimeout(async () => {
      await fetchDocuments()
      schedulePollIfNeeded()
    }, 2000)
  }
}

/** 停止轮询定时器 */
function clearPoll() {
  if (pollTimer !== null) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function handlePageChange() {
  clearPoll()
  fetchDocuments().then(schedulePollIfNeeded)
}

function handleFilterChange() {
  currentPage.value = 1
  clearPoll()
  fetchDocuments().then(schedulePollIfNeeded)
}

function handleUploadSuccess() {
  currentPage.value = 1
  clearPoll()
  fetchDocuments().then(schedulePollIfNeeded)
}

async function handleDelete(id: number) {
  try {
    await ElMessageBox.confirm(t('knowledge.deleteConfirm'), t('common.tips'), {
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      type: 'warning',
    })
    await knowledgeApi.deleteDocument(id)
    ElMessage.success(t('common.success'))
    fetchDocuments().then(schedulePollIfNeeded)
  } catch {
    // 用户取消或删除失败（错误已在拦截器中处理）
  }
}

onMounted(() => {
  fetchDocuments().then(schedulePollIfNeeded)
})

onUnmounted(() => {
  clearPoll()
})
</script>

<template>
  <div class="knowledge-page">
    <div class="page-header">
      <h2 class="page-title">{{ $t('knowledge.title') }}</h2>
      <el-button type="primary" v-permission="'canEditKnowledge'" @click="uploadDialogVisible = true">
        + {{ $t('knowledge.uploadDocument') }}
      </el-button>
    </div>

    <div class="filter-bar">
      <el-select
        v-model="filterDomain"
        :placeholder="$t('knowledge.filterDomain')"
        clearable
        @change="handleFilterChange"
      >
        <el-option :label="$t('common.all')" value="" />
        <el-option
          v-for="d in allowedDomains"
          :key="d"
          :label="d"
          :value="d"
        />
      </el-select>

      <el-select
        v-model="filterSensitivityLevel"
        :placeholder="$t('knowledge.filterClearance')"
        @change="handleFilterChange"
      >
        <el-option :label="$t('common.all')" :value="-1" />
        <el-option
          v-for="s in filteredSensitivityLevels"
          :key="s.value"
          :label="s.label"
          :value="s.value"
        />
      </el-select>
    </div>

    <!-- 批次 07 · 任务 7.0.5：向用户解释"为什么有些内容现在搜不到" -->
    <div class="status-legend">
      <span class="legend-item">
        <el-tag type="success" size="small">就绪</el-tag>
        <span class="legend-text">完全就绪：关键词与语义检索均可命中</span>
      </span>
      <span class="legend-item">
        <el-tag type="warning" size="small">部分就绪</el-tag>
        <span class="legend-text">已入库，关键词可搜；向量化完成后语义检索才可用</span>
      </span>
    </div>

    <DocumentTable
      :documents="documents"
      :loading="loading"
      @delete="handleDelete"
    />

    <Pagination
      :total="total"
      :current-page="currentPage"
      :page-size="pageSize"
      @update:current-page="currentPage = $event"
      @update:page-size="pageSize = $event"
      @change="handlePageChange"
    />

    <UploadDialog
      v-model:visible="uploadDialogVisible"
      :allowed-domains="allowedDomains"
      :max-clearance-level="clearanceLevel"
      @success="handleUploadSuccess"
    />
  </div>
</template>

<style scoped lang="scss">
.knowledge-page {
  .page-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: var(--space-5);

    .page-title {
      margin: 0;
      font-size: var(--font-size-xl);
      font-weight: var(--font-weight-semibold);
      color: $text-primary;
    }
  }

  .filter-bar {
    display: flex;
    gap: var(--space-3);
    margin-bottom: var(--space-4);

    .el-select {
      width: 180px;
    }
  }

  .status-legend {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-4);
    margin-bottom: var(--space-3);
    font-size: var(--font-size-xs);
    color: $text-secondary;

    .legend-item {
      display: inline-flex;
      align-items: center;
      gap: var(--space-2);
    }
  }
}
</style>
