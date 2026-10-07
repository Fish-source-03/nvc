// ==================== 通用类型 ====================

// API 通用响应
export interface ApiResult<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

// 分页
export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

// ==================== 用户相关 ====================

// 用户信息（P2 扩展 ABAC 字段）
export interface UserInfo {
  id: number
  username: string
  realName: string
  email: string
  phone: string
  role: string
  status: number
  // ★ P2 ABAC 字段
  department: string
  clearanceLevel: number
  allowedDomains: string  // 后端返回逗号分隔字符串，前端解析为数组
  title: string
  createTime: string
  updateTime: string
}

// P2 双 Token 登录响应
export interface LoginVO {
  accessToken: string
  refreshToken: string
  expiresIn: number
  userId: number
  username: string
  role: string
  department: string
  clearanceLevel: number
  allowedDomains: string  // 逗号分隔
  title: string
}

// ==================== 文档相关 ====================

// 文档信息（P2 扩展 domain/sensitivityLevel）
export interface DocumentInfo {
  id: number
  title: string
  fileName: string
  filePath: string
  fileType: string
  fileSize: number
  status: string
  uploadUserId: number
  errorMsg: string
  // ★ P2 新增
  domain: string
  sensitivityLevel: number
  sensitivityLabel: string
  createTime: string
  updateTime: string
}

// ==================== 会话与消息 ====================

// 会话
export interface Conversation {
  id: number
  userId: number
  title: string
  messageCount: number
  createTime: string
  updateTime: string
}

// 消息
export interface Message {
  id: number
  conversationId: number
  role: 'user' | 'assistant'
  content: string
  sources: string
  createTime: string
}

// 问答响应
export interface AskResponse {
  answer: string
  conversationId: number
  sources: RetrievedDocument[]
}

// 检索来源
export interface RetrievedDocument {
  documentId: string
  documentTitle: string
  content: string
  similarity: number
}

// ==================== P2 SSE 流式相关类型（★） ====================

export interface SSETokenEvent {
  event: 'token'
  data: string
}

export interface SSEDoneEvent {
  event: 'done'
  data: {
    answer: string
    conversationId: number
    messageId: number
    sources: SourceVO[]
  }
}

export interface SSEErrorEvent {
  event: 'error'
  data: string
}

// 引用来源（提取为独立类型）
export interface SourceVO {
  documentId: number
  documentTitle: string
  content: string
  similarity: number
}

// 反馈评价
export interface FeedbackDTO {
  messageId: number
  feedback: 'positive' | 'negative'
  reason?: string
}

// ==================== 仪表盘 ====================

export interface DashboardVO {
  todayQA: number
  todayNewUsers: number
  totalDocuments: number
  totalChunks: number
  totalUsers: number
  // ★ P2 新增满意度字段
  todayPositive: number
  todayNegative: number
  satisfactionRate: number
  weeklyTrend: DailyStats[]
  docTypeDistribution: Record<string, number>
}

export interface DailyStats {
  id: number
  statDate: string
  qaCount: number
  userQuestionCount: number
  activeUserCount: number
  docUploadCount: number
  // ★ P2 满意度趋势
  satisfactionRate?: number
}

// ==================== P2 数据源管理相关类型（★） ====================

/**
 * 数据源配置（**响应**类型，字段名与后端 `DataSourceConfig` 实体的序列化结果一致）。
 *
 * ★ 批次 11 · R23：定时表达式在响应里叫 `syncCron`（后端实体字段名），
 * 只有**写入**方向才用 `@JsonAlias("cronExpression")` 兼容旧字段名
 * ——读写两侧口径不同，回显必须读 `syncCron`。
 */
export interface DataSourceConfig {
  id: number
  sourceName: string
  sourceType: 'JDBC' | 'REST' | 'S3'
  domain: string
  status: 'ACTIVE' | 'INACTIVE' | 'ERROR'
  syncStrategy: 'FULL' | 'INCREMENTAL'
  /** 定时同步 Cron 表达式（响应字段名；写入时用 {@link DataSourceForm.cronExpression}） */
  syncCron: string
  /** 增量同步游标字段（后端实体返回；表单回显用） */
  cursorField?: string
  lastSyncAt: string
  totalSynced: number
  connectionConfig: Record<string, any>
  contentFields?: string
  createTime: string
  updateTime: string
}

/** 数据源**写入**报文（`cronExpression` 由后端 `@JsonAlias` 接收，勿改为 syncCron） */
export interface DataSourceForm {
  sourceName: string
  sourceType: 'JDBC' | 'REST' | 'S3'
  domain: string
  syncStrategy: 'FULL' | 'INCREMENTAL'
  cursorField?: string
  cronExpression?: string
  connectionConfig: Record<string, any>
  contentFields?: string
}

export interface ConnectionTestResult {
  success: boolean
  latencyMs: number
  dbProduct?: string
  dbVersion?: string
  errorMsg?: string
}

export interface SyncRecord {
  id: number
  datasourceId: number
  syncType: 'FULL' | 'INCREMENTAL'
  status: 'SUCCESS' | 'FAILED' | 'RUNNING'
  totalRows: number
  errorMsg?: string
  syncAt: string
}

// ==================== P2 知识目录相关类型（★） ====================

export interface CatalogTree {
  domains: DomainNode[]
}

export interface DomainNode {
  domainName: string
  sourceCount: number
  totalEntities: number
  sources: SourceNode[]
}

export interface SourceNode {
  sourceId: number
  sourceName: string
  sourceType: string
  lastSyncAt: string
  totalSynced: number
  entities: EntityNode[]
}

export interface EntityNode {
  entityName: string
  entityType: string
  recordCount: number
  lastUpdated: string
}

// ==================== P2 数据质量相关类型（★） ====================

export interface QualityReport {
  batchId: string
  datasourceId: number
  sourceName: string
  totalCount: number
  passCount: number
  failCount: number
  passRate: number
  blocked: boolean
  failures: QualityFailure[]
  checkTime: string
}

/**
 * 质检失败明细（后端 `QualityFailure` DTO 的序列化契约）。
 *
 * ★ 批次 11 · R45：后端在批次 10.4 把失败明细从"按 MD5(规则|原因) 跨记录去重"
 * 改为"按（规则 + 原因）聚合"，因此新增 `ruleType` / `recordCount` / `recordIndices`：
 * 一条明细现在代表**一组**失败记录（不再只代表首次出现的那一条）。
 * 三个字段均可缺失——旧报告 JSON 里只有 `recordIndex`，前端必须能优雅降级。
 */
export interface QualityFailure {
  ruleName: string
  /** 首次出现的失败记录索引（从 0 开始）；旧 JSON 与既有列展示仍依赖它 */
  recordIndex: number
  reason: string
  /** 规则类型编码：completeness / uniqueness / format / encoding / length */
  ruleType?: string
  /** 该明细（同一规则 + 同一原因）聚合的失败记录总数 */
  recordCount?: number
  /** 失败记录索引列表（后端最多保留 100 个，超出部分只计入 recordCount） */
  recordIndices?: number[]
}

// ==================== P2 枚举常量（★） ====================

/** 业务域 */
export const DOMAINS = ['HR', 'FINANCE', 'RD', 'SALES', 'COMMON'] as const
export type Domain = (typeof DOMAINS)[number]

/** 密级选项 */
export const SENSITIVITY_LEVELS = [
  { value: 0, label: '公开' },
  { value: 1, label: '内部' },
  { value: 2, label: '机密' },
  { value: 3, label: '绝密' },
] as const

/** 职级 */
export const TITLES = ['employee', 'manager', 'director'] as const
export type Title = (typeof TITLES)[number]

/** 部门选项 */
export const DEPARTMENTS = [
  { value: 'HR', label: '人力资源' },
  { value: 'FINANCE', label: '财务管理' },
  { value: 'RD', label: '研发中心' },
  { value: 'SALES', label: '销售管理' },
  { value: 'COMMON', label: '公共部门' },
] as const

/** 数据源类型 */
export const SOURCE_TYPES = [
  { value: 'JDBC', label: '数据库 (JDBC)' },
  { value: 'REST', label: 'REST API' },
  { value: 'S3', label: '文件系统 (S3)' },
] as const
