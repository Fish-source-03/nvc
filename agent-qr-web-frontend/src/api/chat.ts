import request, { ensureValidToken } from './index'
import type { ApiResult, Conversation, Message, AskResponse, SourceVO } from '@/types'
import { fetchEventSource, EventStreamContentType } from '@microsoft/fetch-event-source'
import { shouldRetrySse, SSE_RETRY_DELAY_MS } from '@/utils/sse'
import { resolveSseMaxReconnect, resolveSseTimeout } from '@/utils/runtimeConfig'

/**
 * SSE 建连阶段被服务端**拒绝**时的人话文案（批次 11 · R19②，问题 38 收尾）。
 *
 * <p>`fetchEventSource` 不走 axios 实例，因此拿不到响应拦截器的 403 分支：
 * 越域（403）、未登录（401）、参数缺失（400）此前一律退化成
 * `onerror` 里的「连接异常，请重试」——把权限问题伪装成网络问题，
 * 且会对**必然失败**的请求无意义地重连 3 次。</p>
 *
 * @param status HTTP 状态码
 * @returns 精准文案；非"确定性拒绝"（5xx / 网络中断等可重试故障）返回 null
 */
export function describeSseHttpFailure(status: number): string | null {
  switch (status) {
    case 400:
      return '请求参数有误，请确认已选择有效的业务域后重试'
    case 401:
      return '登录已过期，请重新登录'
    case 403:
      return '权限不足：当前账号无权访问该业务域，请切换业务域后重试'
    default:
      return null
  }
}

/**
 * HTTP 200 但响应体不是事件流时的文案（后端把业务错误以 JSON 结果返回，
 * 如 `domain` 缺失时的业务码 400）。
 */
const NON_STREAM_RESPONSE_MESSAGE = '请求被拒绝：请确认已选择有效的业务域后重试'

export const chatApi = {
  // [P1 保留] 同步问答
  // ★ 批次 11 · R19①：补上**必需**的 domain 参数——后端 `/api/chat/ask` 与
  //   `/api/chat/ask/stream` 一样强制校验 domain（缺失 → 业务码 400、越域 → 403），
  //   此前该方法无 domain，一旦被启用必然失败。domain 设为必需（非可选），
  //   把"必须传域"的约束前移到编译期。
  ask(query: string, domain: string, conversationId?: number) {
    return request.post<any, ApiResult<AskResponse>>('/api/chat/ask', { query, domain, conversationId })
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
    /**
     * 建连阶段被确定性拒绝时的精准文案（批次 11 · R19②）。
     * 非 null ⇒ 不再重连，直接把文案交给 `callbacks.onError`。
     */
    let httpRejectMessage: string | null = null
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
        /**
         * ★ 批次 11 · R19②：建连阶段区分「确定性拒绝」与「可重试故障」。
         *
         * 抛出的错误会交给下面的 `onerror` 统一收口（fetchEventSource 的
         * `onopen` 抛错即代表本次连接失败），因此这里只负责**判定与记文案**。
         */
        async onopen(response) {
          const rejectMessage = describeSseHttpFailure(response.status)
          if (rejectMessage) {
            httpRejectMessage = rejectMessage
            throw new Error(`SSE 建连被拒绝: HTTP ${response.status}`)
          }
          if (response.ok && response.headers.get('content-type')?.includes(EventStreamContentType)) {
            return
          }
          if (!response.ok) {
            // 5xx 等可重试故障：沿用既有的重连语义（不记文案）
            throw new Error(`SSE 建连失败: HTTP ${response.status}`)
          }
          // HTTP 200 但不是事件流：后端把业务错误（如 domain 缺失）以 JSON 返回，
          // 这种情况重连也不会成功。
          httpRejectMessage = NON_STREAM_RESPONSE_MESSAGE
          throw new Error('SSE 响应不是事件流')
        },
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
        // 错误对象本身不参与判定：拒绝文案在 onopen 阶段已记录，其余按可重试故障处理
        onerror() {
          clearTimeoutTimer()
          // 主动取消（含超时中止）则静默返回
          if (controller.signal.aborted) {
            return
          }
          // ★ 批次 11 · R19②：403/401/400 等确定性拒绝 → 精准文案，且**不重连**
          //   （越域、未登录、参数缺失都不是"再试一次"能解决的）
          if (httpRejectMessage) {
            const message = httpRejectMessage
            httpRejectMessage = null
            controller.abort()
            callbacks.onError(message)
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
