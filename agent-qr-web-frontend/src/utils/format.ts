/**
 * 格式化文件大小
 */
export function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const k = 1024
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + units[i]
}

/**
 * 格式化日期时间
 */
export function formatDateTime(dateStr: string): string {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  const h = String(date.getHours()).padStart(2, '0')
  const min = String(date.getMinutes()).padStart(2, '0')
  return `${y}-${m}-${d} ${h}:${min}`
}

/**
 * 截断文本
 */
export function truncateText(text: string, maxLength: number): string {
  if (!text) return ''
  return text.length > maxLength ? text.slice(0, maxLength) + '...' : text
}

// ==================== P2 新增格式化函数 ====================

/**
 * 格式化密级 → 中文标签
 */
export function formatSensitivityLevel(level: number): string {
  const map: Record<number, string> = {
    0: '公开',
    1: '内部',
    2: '机密',
    3: '绝密',
  }
  return map[level] ?? '未知'
}

/**
 * 格式化业务域 → 中文标签
 */
export function formatDomain(domain: string): string {
  const map: Record<string, string> = {
    HR: '人力资源',
    FINANCE: '财务管理',
    RD: '研发中心',
    SALES: '销售管理',
    COMMON: '公共部门',
  }
  return map[domain] || domain
}

/**
 * 格式化数据源类型 → 中文标签
 */
export function formatSourceType(type: string): string {
  const map: Record<string, string> = {
    JDBC: '数据库',
    REST: 'REST API',
    S3: '文件系统',
  }
  return map[type] || type
}

/**
 * 格式化同步状态 → 中文标签
 */
export function formatSyncStatus(status: string): string {
  const map: Record<string, string> = {
    ACTIVE: '活跃',
    INACTIVE: '停用',
    ERROR: '异常',
  }
  return map[status] || status
}

/**
 * 格式化合格率 → 百分比字符串
 */
export function formatPassRate(rate: number): string {
  return (rate * 100).toFixed(1) + '%'
}

/**
 * 解析后端返回的逗号分隔域字符串 → 数组
 */
export function parseAllowedDomains(raw: string): string[] {
  if (!raw) return []
  return raw.split(',').map((d) => d.trim()).filter(Boolean)
}

// ==================== 批次 11 · R45：质检失败明细摘要 ====================

/** 质检失败明细的记录摘要（含"共几条 + 具体是哪几条"） */
export interface FailureRecordSummary {
  /** 该明细聚合的失败记录总数 */
  count: number
  /** 用于展示的记录索引（最多 {@link DEFAULT_MAX_FAILURE_INDICES} 个） */
  indices: number[]
  /** 是否还有未展示的记录（总数或索引列表超出展示上限） */
  truncated: boolean
}

/** 失败明细默认展示的记录索引个数（避免长列表撑爆表格） */
export const DEFAULT_MAX_FAILURE_INDICES = 10

/**
 * 汇总一条质检失败明细涉及的记录（批次 11 · R45）。
 *
 * <p>批次 10.4 后后端把失败明细按（规则 + 原因）聚合：
 * `recordCount` 是总数、`recordIndices` 是具体索引（上限 100）；旧报告的 JSON
 * 没有这两个字段，只有首次出现的 `recordIndex` —— 此时退化为"共 1 条"。</p>
 *
 * @param failure 失败明细（字段可缺失）
 * @param maxIndices 展示的索引个数上限（默认 10）
 * @returns 摘要；无任何可用信息时返回 null（调用方展示占位符）
 */
export function summarizeFailureRecords(
  failure: {
    recordCount?: number | null
    recordIndices?: number[] | null
    recordIndex?: number | null
  },
  maxIndices: number = DEFAULT_MAX_FAILURE_INDICES,
): FailureRecordSummary | null {
  const declaredIndices = Array.isArray(failure?.recordIndices) ? failure.recordIndices : []
  let indices = declaredIndices.filter((i): i is number => typeof i === 'number' && Number.isFinite(i))
  // 兼容旧版 JSON：只有 recordIndex（首次出现位置）
  if (indices.length === 0 && typeof failure?.recordIndex === 'number' && Number.isFinite(failure.recordIndex)) {
    indices = [failure.recordIndex]
  }

  const declaredCount =
    typeof failure?.recordCount === 'number' && Number.isFinite(failure.recordCount) && failure.recordCount > 0
      ? failure.recordCount
      : 0
  const count = Math.max(declaredCount, indices.length)
  if (count === 0) {
    return null
  }

  const limit = Math.max(0, Math.floor(maxIndices))
  const shown = limit === 0 ? [] : indices.slice(0, limit)
  return { count, indices: shown, truncated: count > shown.length }
}
