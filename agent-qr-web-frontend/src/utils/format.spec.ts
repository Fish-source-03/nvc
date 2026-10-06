// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { formatFileSize, formatDateTime, truncateText, formatSensitivityLevel } from './format'

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
