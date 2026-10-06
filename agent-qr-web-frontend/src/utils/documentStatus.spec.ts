// @vitest-environment node
import { describe, it, expect } from 'vitest'
import {
  ALL_DOCUMENT_STATUSES,
  DOCUMENT_STATUS_MAP,
  PROCESSING_STATUSES,
  getDocumentStatusDisplay,
  type DocumentStatusDisplay,
} from './documentStatus'

/** 取状态展示定义；缺失时直接抛出，避免 `noUncheckedIndexedAccess` 下的空值扩散 */
function displayOf(status: string): DocumentStatusDisplay {
  const display = DOCUMENT_STATUS_MAP[status]
  if (!display) {
    throw new Error(`状态 ${status} 缺少展示定义`)
  }
  return display
}

/**
 * 文档状态展示映射测试（批次 07 · 任务 7.0a / 7.0.5）。
 *
 * 拦截的核心缺陷（问题 28）：状态机由 7 态改为 8 态后，
 * 若前端映射表漏掉 `INDEXED`，切片已入库但向量未写的文档会**显示原始英文状态名**，
 * 用户无法理解"为什么现在搜不到"。
 */
describe('documentStatus map (批次 07)', () => {
  it('★ 8 个状态都有对应展示定义（UPLOADED/PARSING/CHUNKING/INDEXED/EMBEDDING/READY/FAILED/DELETING）', () => {
    expect(ALL_DOCUMENT_STATUSES).toHaveLength(8)
    expect([...ALL_DOCUMENT_STATUSES].sort()).toEqual(
      ['CHUNKING', 'DELETING', 'EMBEDDING', 'FAILED', 'INDEXED', 'PARSING', 'READY', 'UPLOADED'].sort()
    )
    for (const status of ALL_DOCUMENT_STATUSES) {
      const display = displayOf(status)
      expect(display.label, `${status} 缺少展示文案`).toBeTruthy()
      expect(display.hint, `${status} 缺少 tooltip 说明`).toBeTruthy()
      expect(display.label).not.toBe(status)
    }
  })

  it('★ INDEXED 与 READY 必须可区分：「部分就绪」而非「就绪」', () => {
    const indexed = displayOf('INDEXED')
    const ready = displayOf('READY')

    expect(indexed.label).toBe('部分就绪')
    expect(indexed.type).toBe('warning')
    expect(ready.label).toBe('就绪')
    expect(ready.type).toBe('success')
    expect(indexed.hint).toContain('语义检索暂不可用')
    expect(ready.hint).toContain('语义检索均可用')
  })

  it('★ INDEXED 必须纳入轮询集合，否则停在「部分就绪」不再刷新', () => {
    expect(PROCESSING_STATUSES.has('INDEXED')).toBe(true)
    expect(PROCESSING_STATUSES.has('EMBEDDING')).toBe(true)
    expect(PROCESSING_STATUSES.has('READY')).toBe(false)
    expect(PROCESSING_STATUSES.has('DELETING')).toBe(false)
  })

  it('未知状态回退为原始状态名，不静默显示为空', () => {
    const display = getDocumentStatusDisplay('SOME_NEW_STATUS')
    expect(display.label).toBe('SOME_NEW_STATUS')
    expect(display.type).toBe('info')
  })
})
