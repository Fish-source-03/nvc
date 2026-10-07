import request, { ensureValidToken } from './index'
import type { ApiResult, Conversation, Message, AskResponse, SourceVO } from '@/types'
import { fetchEventSource } from '@microsoft/fetch-event-source'
import { shouldRetrySse, SSE_RETRY_DELAY_MS } from '@/utils/sse'
import { resolveSseMaxReconnect, resolveSseTimeout } from '@/utils/runtimeConfig'

export const chatApi = {
  // [P1 保留] 同步问答
  ask(query: string, conversationId?: number) {
    return request.post<any, ApiResult<AskResponse>>('/api/chat/ask', { query, conversationId })
  },

  // ★ [P2 新增] SSE 流式问答
  // ★ 批次 10 · 任务 10.5.2（问题 38）：接线 VITE_SSE_TIMEOUT / VITE_SSE_MAX_RECONNECT——
  //   此前该路径既无超时也无重连上限（两个环境变量无任何引用）。
  askStream(
    query: string,
    domain: string | null,
    conversationId: number | null,
    callbacks: {
      onToken: (token: string) => void
      onDone: (data: { answer: string; conversationId: number; messageId: number; sources: SourceVO[] }) => void
      onError: (error: string) => void
    },
  ): AbortController {
    const controller = new AbortController()
    const timeoutMs = resolveSseTimeout()
    const maxReconnect = resolveSseMaxReconnect()

    /** 已发起的连接次数（含首次） */
    let attempts = 0
    /** 本次会话是否已收到任何 SSE 消息（token/done/error 均算） */
    let receivedAnyMessage = false
    let timeoutTimer: ReturnType<typeof setTimeout> | null = null
    let retryTimer: ReturnType<typeof setTimeout> | null = null

    const clearTimeoutTimer = () => {
      if (timeoutTimer) {
        clearTimeout(timeoutTimer)
        timeoutTimer = null
      }
    }

    /** 为本次连接装上超时保护（默认 5 分钟，与后端 SseEmitter 超时一致） */
    const armTimeout = () => {
      clearTimeoutTimer()
      if (timeoutMs <= 0) {
        return
      }
      timeoutTimer = setTimeout(() => {
        timeoutTimer = null
        controller.abort()
        callbacks.onError(`响应超时（${timeoutMs}ms）`)
      }, timeoutMs)
    }

    const doFetch = async () => {
      // ★ 主动刷新即将过期的 token（避免 SSE 请求发出后中途过期）
      const validToken = await ensureValidToken()
      if (!validToken) {
        callbacks.onError('登录已过期，请重新登录')
        return
      }
      if (controller.signal.aborted) {
        return
      }

      attempts += 1
      armTimeout()

      fetchEventSource('/api/chat/ask/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${validToken}`,
        },
        body: JSON.stringify({ query, conversationId, domain }),
        signal: controller.signal,
        openWhenHidden: true,
        onmessage(event) {
          receivedAnyMessage = true
          switch (event.event) {
            case 'token':
              callbacks.onToken(event.data)
              break
            case 'done':
              callbacks.onDone(JSON.parse(event.data))
              break
            case 'error':
              callbacks.onError(event.data)
              break
          }
        },
        onerror(err) {
          clearTimeoutTimer()
          // 主动取消（含超时中止）则静默返回
          if (controller.signal.aborted) {
            return
          }
          // ★ 尚未收到任何消息且未达重连上限（VITE_SSE_MAX_RECONNECT）→ 延迟后重连；
          //   已开始输出则不重连（否则前端会重复追加已输出的内容）
          if (
            shouldRetrySse({
              receivedAnyMessage,
              aborted: false,
              attempts,
              maxReconnect,
            })
          ) {
            retryTimer = setTimeout(() => {
              retryTimer = null
              if (!controller.signal.aborted) {
                doFetch()
              }
            }, SSE_RETRY_DELAY_MS)
            return
          }
          // ★ return（而非 throw）阻止 fetchEventSource 重试
          //    throw 反而会触发库的内部重试机制！
          controller.abort()
          callbacks.onError('连接异常，请重试')
          return
        },
        onclose() {
          clearTimeoutTimer()
        },
      })
    }

    // 取消时清理定时器，避免"已取消后仍触发超时/重连"
    controller.signal.addEventListener('abort', () => {
      clearTimeoutTimer()
      if (retryTimer) {
        clearTimeout(retryTimer)
        retryTimer = null
      }
    })

    doFetch()
    return controller
  },

  // ★ [P2 新增] 提交反馈评价
  submitFeedback(messageId: number, feedback: 'positive' | 'negative', reason?: string) {
    return request.post<any, ApiResult<void>>(`/api/statistics/feedback/${messageId}`, { feedback, reason })
  },

  // 以下不变
  listConversations() {
    return request.get<any, ApiResult<Conversation[]>>('/api/chat/conversations')
  },

  getMessages(conversationId: number) {
    return request.get<any, ApiResult<Message[]>>(`/api/chat/conversations/${conversationId}/messages`)
  },

  deleteConversation(id: number) {
    return request.delete<any, ApiResult<void>>(`/api/chat/conversations/${id}`)
  },
}
