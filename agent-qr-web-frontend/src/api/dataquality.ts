import request from './index'
import type { ApiResult, PageResult, QualityReport } from '@/types'

/**
 * 规则类型编码 —— 与后端 {@code QualityRule#getType()} 一一对应：
 * completeness=完整性、uniqueness=唯一性（重复检测）、format=格式、encoding=编码、length=长度。
 * 数据库只存规则类型 + 配置，判定逻辑仍在后端 Java 实现类中（不引入脚本引擎）。
 */
export type QualityRuleType = 'completeness' | 'uniqueness' | 'format' | 'encoding' | 'length'

/** 后端 quality_rule 表的接口契约（GET/POST/PUT 的报文结构） */
export interface QualityRuleDto {
  id?: number
  ruleName: string
  ruleType: QualityRuleType
  /** 目标字段列表（逗号分隔）；为空表示由后端规则实现决定默认检查范围 */
  targetFields?: string | null
  /** 校验参数（JSON 对象）：pattern / minLength / maxLength / charset */
  params?: Record<string, unknown> | null
  enabled: boolean
  priority?: number
  createTime?: string
  updateTime?: string
}

/**
 * 规则管理页的表单模型（扁平结构，便于 el-form 双向绑定）。
 * <p>后端把"目标字段"存列、其余参数存 JSON，二者之间的映射见
 * {@link toRuleDto} / {@link fromRuleDto}。</p>
 */
export interface QualityRule {
  id?: number
  ruleName: string
  ruleType: QualityRuleType
  /** 目标字段（逗号分隔）——完整性/格式/长度规则使用 */
  targetFields?: string
  /** 正则表达式——格式规则使用 */
  pattern?: string
  /** 最小长度——长度规则使用 */
  minLength?: number
  /** 最大长度——长度规则使用 */
  maxLength?: number
  /** 期望字符集——编码规则使用 */
  encodingCharset?: string
  enabled: boolean
  priority?: number
}

/** 把表单模型映射为后端报文（只提交当前类型用得上的参数，避免脏参数落库） */
export function toRuleDto(rule: QualityRule): QualityRuleDto {
  const params: Record<string, unknown> = {}
  if (rule.ruleType === 'format' && rule.pattern) {
    params.pattern = rule.pattern
  }
  if (rule.ruleType === 'length') {
    if (rule.minLength !== undefined && rule.minLength !== null) params.minLength = rule.minLength
    if (rule.maxLength !== undefined && rule.maxLength !== null) params.maxLength = rule.maxLength
  }
  if (rule.ruleType === 'encoding' && rule.encodingCharset) {
    params.charset = rule.encodingCharset
  }

  const dto: QualityRuleDto = {
    ruleName: rule.ruleName,
    ruleType: rule.ruleType,
    targetFields: rule.targetFields ? rule.targetFields : null,
    params,
    enabled: rule.enabled,
  }
  if (rule.id !== undefined) dto.id = rule.id
  if (rule.priority !== undefined && rule.priority !== null) dto.priority = rule.priority
  return dto
}

/** 把后端报文映射为表单模型（params 中的字段按类型摊平） */
export function fromRuleDto(dto: QualityRuleDto): QualityRule {
  const params = dto.params || {}
  const rule: QualityRule = {
    ruleName: dto.ruleName,
    ruleType: dto.ruleType,
    targetFields: dto.targetFields || '',
    enabled: dto.enabled,
  }
  if (dto.id !== undefined) rule.id = dto.id
  if (dto.priority !== undefined && dto.priority !== null) rule.priority = dto.priority
  if (params.pattern !== undefined && params.pattern !== null) {
    rule.pattern = String(params.pattern)
  }
  if (params.minLength !== undefined && params.minLength !== null) {
    rule.minLength = Number(params.minLength)
  }
  if (params.maxLength !== undefined && params.maxLength !== null) {
    rule.maxLength = Number(params.maxLength)
  }
  if (params.charset !== undefined && params.charset !== null) {
    rule.encodingCharset = String(params.charset)
  }
  return rule
}

export const dataqualityApi = {
  listReports(params: { page: number; size: number; blocked?: boolean }) {
    return request.get<any, ApiResult<PageResult<QualityReport>>>('/api/dataquality/reports', { params })
  },

  getReport(batchId: string) {
    return request.get<any, ApiResult<QualityReport>>(`/api/dataquality/reports/${batchId}`)
  },

  // ★ 批次 10 · 任务 10.1：质检规则 CRUD（替换 RulesManager 的 localStorage 假数据）
  listRules() {
    return request.get<any, ApiResult<QualityRuleDto[]>>('/api/dataquality/rules')
  },

  createRule(rule: QualityRuleDto) {
    return request.post<any, ApiResult<QualityRuleDto>>('/api/dataquality/rules', rule)
  },

  updateRule(id: number, rule: QualityRuleDto) {
    return request.put<any, ApiResult<QualityRuleDto>>(`/api/dataquality/rules/${id}`, rule)
  },

  setRuleEnabled(id: number, enabled: boolean) {
    return request.put<any, ApiResult<QualityRuleDto>>(`/api/dataquality/rules/${id}/enabled`, null, {
      params: { enabled },
    })
  },

  deleteRule(id: number) {
    return request.delete<any, ApiResult<void>>(`/api/dataquality/rules/${id}`)
  },
}
