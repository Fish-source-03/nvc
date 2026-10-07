// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest'

const requestMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('./index', () => ({ default: requestMock }))

import { dataqualityApi, fromRuleDto, toRuleDto } from './dataquality'
import type { QualityRule } from './dataquality'
// 以原始文本导入页面源码（vite ?raw），用于静态回归护栏
import rulesManagerSource from '../views/quality/RulesManager.vue?raw'

/**
 * 批次 10 · 任务 10.1（问题 35）—— 规则管理页的数据源切换。
 *
 * <p>修复前：RulesManager.vue 完全依赖 localStorage，规则不参与真实质检。
 * 现在：规则走后端 `/api/dataquality/rules` CRUD。本测试锁定
 * ①接口路径/方法正确；②表单模型与后端报文的映射（params JSON）正确；
 * ③页面源码不再出现 localStorage（回归护栏）。</p>
 */
describe('dataqualityApi · 质检规则 CRUD（batch-10 / 问题 35）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('查询规则列表 → GET /api/dataquality/rules', () => {
    dataqualityApi.listRules()

    expect(requestMock.get).toHaveBeenCalledTimes(1)
    expect(requestMock.get).toHaveBeenCalledWith('/api/dataquality/rules')
  })

  it('新建规则 → POST /api/dataquality/rules（携带后端报文）', () => {
    const dto = {
      ruleName: '邮箱非空',
      ruleType: 'completeness' as const,
      targetFields: 'email',
      params: {},
      enabled: true,
    }

    dataqualityApi.createRule(dto)

    expect(requestMock.post).toHaveBeenCalledWith('/api/dataquality/rules', dto)
  })

  it('更新规则 → PUT /api/dataquality/rules/{id}', () => {
    const dto = { ruleName: 'x', ruleType: 'encoding' as const, enabled: false }

    dataqualityApi.updateRule(7, dto)

    expect(requestMock.put).toHaveBeenCalledWith('/api/dataquality/rules/7', dto)
  })

  it('启停规则 → PUT /api/dataquality/rules/{id}/enabled?enabled=', () => {
    dataqualityApi.setRuleEnabled(7, false)

    expect(requestMock.put).toHaveBeenCalledWith('/api/dataquality/rules/7/enabled', null, {
      params: { enabled: false },
    })
  })

  it('删除规则 → DELETE /api/dataquality/rules/{id}', () => {
    dataqualityApi.deleteRule(7)

    expect(requestMock.delete).toHaveBeenCalledWith('/api/dataquality/rules/7')
  })
})

describe('规则表单模型 ↔ 后端报文映射（batch-10 / 问题 35）', () => {
  it('完整性规则：目标字段进 targetFields 列，params 为空对象', () => {
    const form: QualityRule = {
      ruleName: '邮箱非空',
      ruleType: 'completeness',
      targetFields: 'email,name',
      enabled: true,
    }

    const dto = toRuleDto(form)

    expect(dto.targetFields).toBe('email,name')
    expect(dto.params).toEqual({})
  })

  it('格式规则：正则进 params.pattern', () => {
    const dto = toRuleDto({
      ruleName: '邮箱格式',
      ruleType: 'format',
      targetFields: 'email',
      pattern: '^\\S+@\\S+$',
      enabled: true,
    })

    expect(dto.params).toEqual({ pattern: '^\\S+@\\S+$' })
  })

  it('长度规则：区间进 params.minLength/maxLength', () => {
    const dto = toRuleDto({
      ruleName: '名称长度',
      ruleType: 'length',
      targetFields: 'name',
      minLength: 2,
      maxLength: 64,
      enabled: true,
    })

    expect(dto.params).toEqual({ minLength: 2, maxLength: 64 })
  })

  it('编码规则：字符集进 params.charset', () => {
    const dto = toRuleDto({
      ruleName: '编码检查',
      ruleType: 'encoding',
      encodingCharset: 'GBK',
      enabled: false,
    })

    expect(dto.params).toEqual({ charset: 'GBK' })
    expect(dto.enabled).toBe(false)
  })

  it('唯一性规则：不携带任何无关参数（避免"界面能配、后端不读"的脏参数）', () => {
    const dto = toRuleDto({
      ruleName: '主键唯一',
      ruleType: 'uniqueness',
      targetFields: 'id',
      pattern: 'ignored',
      minLength: 1,
      enabled: true,
    })

    expect(dto.params).toEqual({})
    // 目标字段对重复检测规则无意义，但仍保留在列中（不参与判定）
    expect(dto.targetFields).toBe('id')
  })

  it('后端报文 → 表单模型：params 摊平且往返一致', () => {
    const form: QualityRule = {
      id: 3,
      ruleName: '名称长度',
      ruleType: 'length',
      targetFields: 'name',
      minLength: 2,
      maxLength: 64,
      enabled: true,
      priority: 10,
    }

    const roundTrip = fromRuleDto(toRuleDto(form))

    expect(roundTrip).toEqual(form)
  })

  it('后端返回空目标字段/空 params 时为 undefined 安全', () => {
    const form = fromRuleDto({
      id: 1,
      ruleName: '完整性检查',
      ruleType: 'completeness',
      targetFields: null,
      params: null,
      enabled: true,
    })

    expect(form.targetFields).toBe('')
    expect(form.pattern).toBeUndefined()
    expect(form.minLength).toBeUndefined()
  })
})

describe('RulesManager.vue · localStorage 回归护栏（batch-10 / 问题 35）', () => {
  const source = rulesManagerSource

  it('页面不再读写 localStorage（原缺陷：规则只存在浏览器本地）', () => {
    // 只检查真实调用（注释中允许出现 localStorage 一词的说明文字）
    expect(source).not.toContain('localStorage.')
    expect(source).not.toContain('quality-rules')
  })

  it('页面数据源为后端规则接口', () => {
    expect(source).toContain('dataqualityApi.listRules')
    expect(source).toContain('dataqualityApi.createRule')
    expect(source).toContain('dataqualityApi.updateRule')
    expect(source).toContain('dataqualityApi.deleteRule')
    expect(source).toContain('dataqualityApi.setRuleEnabled')
  })

  it('页面保留既有结构与交互（表格 / 开关 / 弹窗编辑器）', () => {
    expect(source).toContain('el-table')
    expect(source).toContain('el-switch')
    expect(source).toContain('RuleEditor')
  })
})
