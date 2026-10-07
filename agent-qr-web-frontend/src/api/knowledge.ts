import request from './index'
import type { ApiResult, PageResult, DocumentInfo } from '@/types'

export const knowledgeApi = {
  // ★ P2 升级：新增 domain/sensitivityLevel 参数
  upload(file: File, title?: string, domain?: string, sensitivityLevel?: number) {
    const formData = new FormData()
    formData.append('file', file)
    if (title) formData.append('title', title)
    if (domain) formData.append('domain', domain)
    if (sensitivityLevel != null) formData.append('sensitivityLevel', String(sensitivityLevel))
    return request.post<any, ApiResult<DocumentInfo>>('/api/knowledge/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },

  // ★ 批次 11 · R38③：**移除** keyword 参数。
  //   后端 `KnowledgeController.listDocuments` 只接受 page/size/domain/sensitivityLevel
  //   （`DocumentQueryService.listDocuments(page, size, domain, sensitivityLevel)`），
  //   并没有关键词检索能力。此前类型里挂着 keyword 却无人传参——若照此接线，
  //   搜索框会"能输入、无效果"（静默忽略），比类型不一致更难排查。
  //   注意：用户列表（`api/user.ts`）的 keyword 是**真实存在**的（AdminController 支持
  //   用户名/姓名模糊匹配），两者不可类推。
  //   如需文档关键词搜索，应先由后端提供该参数，再在此处与 KnowledgeView 一并接线。
  listDocuments(params: { page: number; size: number; domain?: string; sensitivityLevel?: number }) {
    return request.get<any, ApiResult<PageResult<DocumentInfo>>>('/api/knowledge/documents', { params })
  },

  getDocument(id: number) {
    return request.get<any, ApiResult<DocumentInfo>>(`/api/knowledge/documents/${id}`)
  },

  deleteDocument(id: number) {
    return request.delete<any, ApiResult<void>>(`/api/knowledge/documents/${id}`)
  },

  getStatus(id: number) {
    return request.get<any, ApiResult<{ status: string; errorMsg?: string }>>(`/api/knowledge/documents/${id}/status`)
  },

  getChunks(id: number) {
    return request.get<any, ApiResult<any[]>>(`/api/knowledge/documents/${id}/chunks`)
  },
}
