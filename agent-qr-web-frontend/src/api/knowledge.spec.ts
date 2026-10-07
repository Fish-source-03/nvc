// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest'

const requestMock = vi.hoisted(() => ({
  get: vi.fn<(...args: unknown[]) => unknown>(),
  post: vi.fn<(...args: unknown[]) => unknown>(),
  put: vi.fn<(...args: unknown[]) => unknown>(),
  delete: vi.fn<(...args: unknown[]) => unknown>(),
}))

vi.mock('./index', () => ({ default: requestMock }))

import { knowledgeApi } from './knowledge'
// 以原始文本导入源码（vite ?raw），用于静态回归护栏
import knowledgeSource from './knowledge.ts?raw'
import knowledgeViewSource from '../views/knowledge/KnowledgeView.vue?raw'

/**
 * 批次 11 · R38③：文档列表的 `keyword` 幽灵参数。
 *
 * <p>缺陷：`knowledgeApi.listDocuments` 的类型里挂着 `keyword`，而
 * `KnowledgeView.vue` 从未传它——两端不一致。核对后端后确认：
 * `KnowledgeController#listDocuments` 只接受 page/size/domain/sensitivityLevel，
 * **没有**关键词检索能力，因此该参数属于"接线了也没效果"的幽灵参数
 * （对照组：`api/user.ts` 的 keyword 由 `AdminController` 真正支持，
 * 二者不可类推）。</p>
 *
 * <p>本次决策：**移除**（而非接线一个静默无效的搜索框）。若将来需要文档搜索，
 * 应先在后端补上参数，再同时改这里与 KnowledgeView。</p>
 */
describe('knowledgeApi · 文档列表契约（batch-11 / R38③）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('listDocuments → GET /api/knowledge/documents，参数原样透传', () => {
    knowledgeApi.listDocuments({ page: 1, size: 10, domain: 'HR', sensitivityLevel: 1 })

    expect(requestMock.get).toHaveBeenCalledWith('/api/knowledge/documents', {
      params: { page: 1, size: 10, domain: 'HR', sensitivityLevel: 1 },
    })
  })

  it('只传分页参数时，请求参数里不出现任何非分页字段（含 keyword）', () => {
    knowledgeApi.listDocuments({ page: 2, size: 20 })

    const params = (requestMock.get.mock.calls[0]![1] as { params: Record<string, unknown> }).params

    expect(Object.keys(params).sort()).toEqual(['page', 'size'])
    expect(params).not.toHaveProperty('keyword')
  })

  it('★ 源码中不再声明 keyword（幽灵参数不得回归）', () => {
    expect(knowledgeSource).not.toMatch(/keyword\s*[?:]/)
  })

  it('★ KnowledgeView 不构造 keyword（保持与后端能力一致）', () => {
    expect(knowledgeViewSource).not.toContain('keyword')
  })
})
