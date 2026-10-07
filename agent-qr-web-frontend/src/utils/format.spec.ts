// @vitest-environment node
import { describe, it, expect } from 'vitest'
import {
  formatFileSize,
  formatDateTime,
  truncateText,
  formatSensitivityLevel,
  summarizeFailureRecords,
  DEFAULT_MAX_FAILURE_INDICES,
} from './format'

/**
 * 前端测试基础设施冒烟测试（批次 01 · 任务 1.0.4）。
 * <p>用于验证 vitest 构建链路通畅，并锁定纯函数工具的既有行为。</p>
 */
describe('format utils (smoke)', () => {
  it('formatFileSize 应按 1024 进制换算并保留一位小数', () => {
    expect(formatFileSize(0)).toBe('0 B')
    expect(formatFileSize(1024)).toBe('1 KB')
    expect(formatFileSize(1536)).toBe('1.5 KB')
  })

  it('formatDateTime 空串返回空串，正常值补零格式化', () => {
    expect(formatDateTime('')).toBe('')
    expect(formatDateTime('2026-10-06T09:05:00')).toBe('2026-10-06 09:05')
  })

  it('truncateText 超长时截断并追加省略号', () => {
    expect(truncateText('', 5)).toBe('')
    expect(truncateText('abcdefg', 5)).toBe('abcde...')
    expect(truncateText('abc', 5)).toBe('abc')
  })

  it('formatSensitivityLevel 未知级别返回“未知”', () => {
    expect(formatSensitivityLevel(0)).toBe('公开')
    expect(formatSensitivityLevel(9)).toBe('未知')
  })
})

/**
 * 批次 11 · R45：质检失败明细的记录摘要。
 *
 * 后端批次 10.4 把失败明细改为按（规则 + 原因）聚合：`recordCount` 是总数、
 * `recordIndices` 是具体索引（上限 100）。前端此前只有 `recordIndex` 一个字段，
 * "1 万条内容为空只剩 1 条明细"的情况下无法回答"具体哪几条失败了"。
 */
describe('summarizeFailureRecords (batch-11 / R45)', () => {
  it('★ 聚合明细：总数取 recordCount，索引原样展示', () => {
    const summary = summarizeFailureRecords({ recordCount: 3, recordIndices: [0, 1, 2], recordIndex: 0 })

    expect(summary).toEqual({ count: 3, indices: [0, 1, 2], truncated: false })
  })

  it('★ 总数远大于索引列表（后端只留前 100 个）→ 标记为截断', () => {
    const summary = summarizeFailureRecords({ recordCount: 5000, recordIndices: [0, 1, 2], recordIndex: 0 })

    expect(summary?.count).toBe(5000)
    expect(summary?.indices).toEqual([0, 1, 2])
    expect(summary?.truncated).toBe(true)
  })

  it('★ 旧版 JSON（只有 recordIndex）→ 退化为"共 1 条"', () => {
    expect(summarizeFailureRecords({ recordIndex: 7 })).toEqual({ count: 1, indices: [7], truncated: false })
  })

  it('索引列表超过展示上限时只展示前 N 个并标记截断', () => {
    const indices = Array.from({ length: 20 }, (_, i) => i)
    const summary = summarizeFailureRecords({ recordCount: 20, recordIndices: indices })

    expect(summary?.indices).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9])
    expect(summary?.indices).toHaveLength(DEFAULT_MAX_FAILURE_INDICES)
    expect(summary?.truncated).toBe(true)
  })

  it('recordIndices 显式为 null（后端反序列化防御）→ 回退到 recordIndex', () => {
    expect(summarizeFailureRecords({ recordCount: 1, recordIndices: null, recordIndex: 4 })).toEqual({
      count: 1,
      indices: [4],
      truncated: false,
    })
  })

  it('没有任何可用信息时返回 null（调用方展示占位符）', () => {
    expect(summarizeFailureRecords({})).toBeNull()
    expect(summarizeFailureRecords({ recordCount: 0, recordIndices: [] })).toBeNull()
    expect(summarizeFailureRecords({ recordCount: -3, recordIndices: [] })).toBeNull()
  })

  it('索引列表里的非法值被忽略，但总数仍以 recordCount 为准', () => {
    const summary = summarizeFailureRecords({
      recordCount: 2,
      // 后端理论上不会给出非数字，这里验证前端不因脏数据崩溃
      recordIndices: [0, Number.NaN, 1] as number[],
    })

    expect(summary?.indices).toEqual([0, 1])
    expect(summary?.count).toBe(2)
    expect(summary?.truncated).toBe(false)
  })

  it('展示上限为 0 时仍给出总数（只展示"共 N 条"）', () => {
    const summary = summarizeFailureRecords({ recordCount: 5, recordIndices: [0, 1] }, 0)

    expect(summary).toEqual({ count: 5, indices: [], truncated: true })
  })

  it('recordCount 缺失时用索引列表长度兜底', () => {
    expect(summarizeFailureRecords({ recordIndices: [3, 9] })).toEqual({
      count: 2,
      indices: [3, 9],
      truncated: false,
    })
  })
})
