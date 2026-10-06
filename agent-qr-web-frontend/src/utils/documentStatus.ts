/**
 * 文档 / 切片状态展示映射（批次 07 · 任务 7.0.5）。
 *
 * 为什么单独成模块：状态映射属纯函数，需要可被 vitest（node 环境）直接断言。
 * jsdom 在本机不可用，写在 SFC 的 `<script setup>` 里的常量无法被外部测试引用。
 *
 * 双状态机的核心区分（问题 28）：
 * - `READY`   「完全就绪」：向量已写入 ChromaDB，关键词与语义检索都能命中；
 * - `INDEXED` 「部分就绪」：切片已入库、关键词（BM25）可搜，但向量尚未写入，
 *             语义检索暂时搜不到 —— 必须让用户知道"为什么有些内容现在搜不到"。
 */

/** el-tag 类型 */
export type StatusTagType = 'info' | 'success' | 'danger' | 'warning' | ''

/** 单个状态的展示定义 */
export interface DocumentStatusDisplay {
  /** el-tag 类型 */
  type: StatusTagType
  /** 展示文案 */
  label: string
  /** 是否展示 loading 图标（处理中的状态） */
  loading: boolean
  /** tooltip 说明 */
  hint: string
}

/**
 * 8 个状态的完整展示映射（UPLOADED/PARSING/CHUNKING/INDEXED/EMBEDDING/READY/FAILED/DELETING）。
 */
export const DOCUMENT_STATUS_MAP: Record<string, DocumentStatusDisplay> = {
  UPLOADED: {
    type: 'info',
    label: '已上传',
    loading: false,
    hint: '已上传，等待解析',
  },
  PARSING: {
    type: '',
    label: '解析中',
    loading: true,
    hint: '正在解析文档内容',
  },
  CHUNKING: {
    type: '',
    label: '切片中',
    loading: true,
    hint: '正在切分文本切片',
  },
  INDEXED: {
    type: 'warning',
    label: '部分就绪',
    loading: false,
    hint: '已入库：关键词检索可用，向量化中，语义检索暂不可用',
  },
  EMBEDDING: {
    type: '',
    label: '向量化中',
    loading: true,
    hint: '向量化中：关键词检索可用，语义检索暂不可用',
  },
  READY: {
    type: 'success',
    label: '就绪',
    loading: false,
    hint: '完全就绪：关键词与语义检索均可用',
  },
  FAILED: {
    type: 'danger',
    label: '失败',
    loading: false,
    hint: '处理失败，请查看错误信息',
  },
  DELETING: {
    type: '',
    label: '删除中',
    loading: true,
    hint: '正在删除',
  },
}

/** 全部合法状态（供测试断言"8 个状态全覆盖"） */
export const ALL_DOCUMENT_STATUSES = Object.keys(DOCUMENT_STATUS_MAP)

/**
 * 仍在中途、需要前端持续轮询的状态集合。
 * <p>
 * `INDEXED` 必须在内：此时向量尚未写入，用户看到的"部分就绪"是暂时的，
 * 不轮询会一直停留在"部分就绪"（索引构建的可见性由批次 07 任务 7.0a 保证）。
 * `DELETING` 不在内：后端 `@TableLogic` 会自动过滤已删除文档，无需轮询。
 * </p>
 */
export const PROCESSING_STATUSES = new Set([
  'UPLOADED',
  'PARSING',
  'CHUNKING',
  'INDEXED',
  'EMBEDDING',
])

/** 取状态展示定义；未知状态回退为原始状态名（不静默显示为空） */
export function getDocumentStatusDisplay(status: string): DocumentStatusDisplay {
  return (
    DOCUMENT_STATUS_MAP[status] ?? {
      type: 'info',
      label: status,
      loading: false,
      hint: status,
    }
  )
}
