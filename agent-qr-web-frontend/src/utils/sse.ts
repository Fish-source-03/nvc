/**
 * SSE 流式请求工具
 * 封装 @microsoft/fetch-event-source，提供统一的 SSE 请求能力
 *
 * ★ 批次 10 · 任务 10.5.2（问题 38）：接线两个此前无引用的环境变量——
 *   - `VITE_SSE_TIMEOUT`：单次 SSE 请求的超时（默认 5 分钟，与后端 SseEmitter 一致）；
 *   - `VITE_SSE_MAX_RECONNECT`：断线重连上限（默认 3 次，且**仅在尚未收到任何消息时**重试——
 *     已开始输出的流重试会重复推送内容）。
 */
import { fetchEventSource, EventStreamContentType } from '@microsoft/fetch-event-source'
import { getAccessToken } from './token'
import { resolveSseMaxReconnect, resolveSseTimeout } from './runtimeConfig'

/** 重连前的等待时间（毫秒） */
export const SSE_RETRY_DELAY_MS = 1_000

export interface SSEOptions {
  url: string
  method?: 'GET' | 'POST'
  body?: any
  headers?: Record<string, string>
  signal?: AbortSignal
  onMessage: (event: string, data: string) => void
  onError?: (error: string) => void
  onClose?: () => void
}

/**
 * 判断一次 SSE 错误是否应触发重连（纯函数，便于用例固化"配置生效"）。
 *
 * 重连条件（三者同时满足）：
 * 1. 不是调用方主动取消（`aborted`）；
 * 2. 本次连接尚未收到任何消息（`receivedAnyMessage === false`）——
 *    已收到部分内容再重试会导致前端重复追加文本；
 * 3. 尚未达到 `VITE_SSE_MAX_RECONNECT` 配置的次数上限。
 *
 * @param input 判定输入
 * @returns true 表示应当重连
 */
export function shouldRetrySse(input: {
  receivedAnyMessage: boolean
  aborted: boolean
  attempts: number
  maxReconnect: number
}): boolean {
  if (input.aborted || input.receivedAnyMessage) {
    return false
  }
  return input.attempts <= input.maxReconnect
}

/**
 * 发起 SSE 流式请求
 * 支持 POST + 自定义 Header（含 Bearer Token）
 * 返回 AbortController 供调用方取消
 */
export function createSSERequest(options: SSEOptions): AbortController {
  const controller = new AbortController()
  const mergedSignal = options.signal
    ? combineSignals(options.signal, controller.signal)
    : controller.signal

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...options.headers,
  }

  const token = getAccessToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  const timeoutMs = resolveSseTimeout()
  const maxReconnect = resolveSseMaxReconnect()
  let receivedAnyMessage = false
  let attempts = 0
  let timeoutTimer: ReturnType<typeof setTimeout> | null = null

  /** 清理超时计时器 */
  const clearTimer = () => {
    if (timeoutTimer) {
      clearTimeout(timeoutTimer)
      timeoutTimer = null
    }
  }

  /** 启动（或重置）本次尝试的超时计时器 */
  const armTimer = () => {
    clearTimer()
    if (timeoutMs <= 0) {
      return
    }
    timeoutTimer = setTimeout(() => {
      timeoutTimer = null
      controller.abort()
      options.onError?.(`SSE 请求超时（${timeoutMs}ms）`)
    }, timeoutMs)
  }

  attempts += 1
  armTimer()

  fetchEventSource(options.url, {
    method: options.method || 'POST',
    headers,
    body: options.body ? JSON.stringify(options.body) : undefined,
    signal: mergedSignal,
    async onopen(response) {
      if (response.ok && response.headers.get('content-type')?.includes(EventStreamContentType)) {
        return // 连接成功
      }
      throw new Error(`SSE 连接失败: HTTP ${response.status}`)
    },
    onmessage(event) {
      receivedAnyMessage = true
      options.onMessage(event.event, event.data)
    },
    onerror(err) {
      // 调用方取消（含超时中止）→ 不再重试、不再提示
      if (controller.signal.aborted) {
        clearTimer()
        throw err
      }
      if (
        shouldRetrySse({
          receivedAnyMessage,
          aborted: false,
          attempts,
          maxReconnect,
        })
      ) {
        attempts += 1
        armTimer()
        // 返回数字 = 让 fetchEventSource 在延迟后重连
        return SSE_RETRY_DELAY_MS
      }
      clearTimer()
      options.onError?.(err.message)
      throw err // 不自动重连
    },
    onclose() {
      clearTimer()
      options.onClose?.()
    },
  })

  return controller
}

/** 合并多个 AbortSignal */
function combineSignals(...signals: AbortSignal[]): AbortSignal {
  const controller = new AbortController()
  signals.forEach((signal) => {
    if (signal.aborted) {
      controller.abort(signal.reason)
      return
    }
    signal.addEventListener('abort', () => controller.abort(signal.reason))
  })
  return controller.signal
}
