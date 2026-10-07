import { ref, onUnmounted, type Ref } from 'vue'

export type ConnectionState = 'disconnected' | 'connecting' | 'connected'

/**
 * 服务端推送的消息体（批次 10 · 任务 10.2.3）。
 */
export interface DocumentProgressMessage {
  type: 'DOCUMENT_PROGRESS'
  documentId: number
  status: string
  statusText?: string
  title?: string
  chunkCount?: number
  errorMsg?: string
  timestamp?: string
}

/**
 * 运维告警消息体（批次 10 · 任务 10.2.4，仅管理员订阅）。
 */
export interface OpsAlertMessage {
  type: 'OPS_ALERT'
  level: 'CRITICAL' | 'WARNING'
  alertType: string
  message: string
  details?: Record<string, unknown>
  timestamp?: string
}

/**
 * WebSocket 订阅回调（批次 10 · 任务 10.2.5 接线，问题 34）。
 */
export interface UseWebSocketOptions {
  /** 收到文档处理进度时回调（订阅 /user/queue/documents/progress） */
  onDocumentProgress?: (payload: DocumentProgressMessage) => void
  /** 收到运维告警时回调（订阅 /topic/ops.alerts，仅管理员可用） */
  onOpsAlert?: (payload: OpsAlertMessage) => void
  /** 是否订阅运维告警频道（仅管理员传 true；服务端还会再校验一次角色） */
  subscribeOpsAlerts?: boolean
}

/** 用户专属的文档进度目的地（服务端按会话投递，天然隔离） */
export const DOCUMENT_PROGRESS_DESTINATION = '/user/queue/documents/progress'

/** 运维告警频道（服务端仅允许管理员订阅） */
export const OPS_ALERT_TOPIC = '/topic/ops.alerts'

/** 建立连接的最长等待时间（毫秒）——超时后按"未连接"处理，由重连逻辑继续尝试 */
const CONNECT_TIMEOUT_MS = 5000

/**
 * WebSocket STOMP 连接管理 Composable（P3 新增）。
 *
 * 批次 10 · 任务 10.2（问题 34）后端补齐后的前端适配：
 * - 端点与后端 `/ws` 对齐，且**容忍 `VITE_WS_URL` 已带 `/ws` 的旧写法**
 *   （`.env.development` 里是 `http://localhost:9090/ws`，直接拼接会得到 `/ws/ws`）；
 * - JWT 通过**握手查询参数** `?token=` 传递（浏览器 WebSocket/SockJS 无法自定义请求头，
 *   而后端 `/ws/**` 已收敛为 `authenticated()`）；STOMP CONNECT 仍会带 Authorization 头，
 *   服务端两条通道都会校验；
 * - `subscribe` 不再是死代码：连接成功后自动订阅**用户专属**的文档进度队列
 *   `/user/queue/documents/progress`（可选订阅运维频道 `/topic/ops.alerts`）；
 * - 连接失败保留降级到 SSE 的能力（`ChatView.vue` 的 SSE 降级逻辑不动）。
 */
export function useWebSocket(brokerURL?: string, options: UseWebSocketOptions = {}) {
  const connectionState: Ref<ConnectionState> = ref('disconnected')
  // @ts-ignore — stompjs 和 sockjs-client 由 pnpm 安装后可用
  let stompClient: any = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let reconnectAttempts = 0
  const maxReconnectAttempts = 10
  /** 已建立的订阅（重连后重建） */
  const subscriptions: any[] = []

  // ★ 批次 11 · R36：兜底值不再写死 `http://localhost:9090`（生产包会指向访问者本机），
  //   改走 resolveWsEndpoint —— 缺省即同源 `/ws`，与 normalizeWsUrl 的口径一致。
  const wsUrl = resolveWsEndpoint(brokerURL, import.meta.env.VITE_WS_URL)

  /**
   * 建立 STOMP over WebSocket 连接。
   *
   * @param token JWT access token
   */
  async function connect(token: string): Promise<void> {
    if (connectionState.value === 'connected' || connectionState.value === 'connecting') {
      return
    }
    connectionState.value = 'connecting'

    try {
      // 动态导入 stompjs（避免编译时依赖缺失）
      const { Client } = await import('@stomp/stompjs')
      // SockJS 用于不支持原生 WebSocket 的浏览器
      const SockJS = (await import('sockjs-client')).default

      // 握手令牌走查询参数（SockJS 会把它透传到 /info 与各传输请求）
      const handshakeUrl = appendToken(wsUrl, token)

      let settleConnect: (() => void) | null = null
      const connected = new Promise<void>((resolve) => {
        settleConnect = resolve
      })
      const settleOnce = () => {
        if (settleConnect) {
          const fn = settleConnect
          settleConnect = null
          fn()
        }
      }
      const connectTimeout = setTimeout(settleOnce, CONNECT_TIMEOUT_MS)

      stompClient = new Client({
        webSocketFactory: () => new SockJS(handshakeUrl),
        connectHeaders: {
          Authorization: `Bearer ${token}`
        },
        reconnectDelay: 5000,
        heartbeatIncoming: 10000,
        heartbeatOutgoing: 10000,
        debug: (msg: string) => {
          if (import.meta.env.DEV) {
            console.debug('[STOMP]', msg)
          }
        },

        onConnect: () => {
          connectionState.value = 'connected'
          reconnectAttempts = 0
          clearTimeout(connectTimeout)
          console.log('[WebSocket] 已连接')
          // ★ 批次 10 · 任务 10.2.5：接入实际订阅（此前 subscribe 是死代码）
          subscribeStandardDestinations()
          settleOnce()
        },

        onDisconnect: () => {
          connectionState.value = 'disconnected'
          console.log('[WebSocket] 已断开')
          settleOnce()
        },

        onStompError: (frame: any) => {
          console.error('[STOMP] 错误:', frame.headers?.message || frame)
          connectionState.value = 'disconnected'
          settleOnce()
        },

        onWebSocketClose: () => {
          connectionState.value = 'disconnected'
          clearTimeout(connectTimeout)
          clearSubscriptions()
          settleOnce()
          if (reconnectAttempts < maxReconnectAttempts) {
            scheduleReconnect(token)
          }
        }
      })

      stompClient.activate()
      await connected
    } catch (e) {
      console.warn('[WebSocket] STOMP 初始化失败，将降级使用 SSE:', e)
      connectionState.value = 'disconnected'
    }
  }

  /**
   * 订阅标准目的地（连接成功后调用）：
   * 1. 用户专属的文档处理进度队列——始终订阅；
   * 2. 运维告警频道——仅当调用方声明管理员身份时订阅（服务端还会再校验）。
   */
  function subscribeStandardDestinations(): void {
    const progressSubscription = subscribe(DOCUMENT_PROGRESS_DESTINATION, (message: any) => {
      try {
        options.onDocumentProgress?.(message as DocumentProgressMessage)
      } catch (e) {
        console.warn('[WebSocket] 文档进度回调异常:', e)
      }
    })
    if (progressSubscription) {
      subscriptions.push(progressSubscription)
    }

    if (options.subscribeOpsAlerts) {
      const opsSubscription = subscribe(OPS_ALERT_TOPIC, (message: any) => {
        try {
          options.onOpsAlert?.(message as OpsAlertMessage)
        } catch (e) {
          console.warn('[WebSocket] 运维告警回调异常:', e)
        }
      })
      if (opsSubscription) {
        subscriptions.push(opsSubscription)
      }
    }
  }

  /** 清理订阅（重连/断开时调用，避免重复订阅） */
  function clearSubscriptions(): void {
    subscriptions.forEach((subscription) => {
      try {
        subscription?.unsubscribe?.()
      } catch {
        // 忽略重复取消
      }
    })
    subscriptions.length = 0
  }

  /** 计划重连 */
  function scheduleReconnect(token: string) {
    if (reconnectTimer) return
    connectionState.value = 'connecting'
    const delay = Math.min(5000 * Math.pow(2, reconnectAttempts), 60000)
    console.log(`[WebSocket] ${delay / 1000}s 后重连 (attempt ${reconnectAttempts + 1})`)
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      reconnectAttempts++
      connect(token)
    }, delay)
  }

  /** 断开连接 */
  function disconnect(): void {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    clearSubscriptions()
    if (stompClient) {
      try {
        stompClient.deactivate()
      } catch (e) {
        console.warn('[WebSocket] 断开异常:', e)
      }
      stompClient = null
    }
    connectionState.value = 'disconnected'
  }

  /**
   * 发送消息到 STOMP 目标。
   *
   * @param destination STOMP 目标路径（如 /app/chat/ask）
   * @param body 消息体
   */
  function send(destination: string, body: unknown): void {
    if (!stompClient || connectionState.value !== 'connected') {
      console.warn('[WebSocket] 未连接，无法发送消息')
      return
    }
    stompClient.publish({
      destination,
      body: typeof body === 'string' ? body : JSON.stringify(body)
    })
  }

  /**
   * 订阅 STOMP 目标。
   *
   * @param destination 订阅路径
   * @param callback 消息回调
   * @returns 订阅对象（可用于取消订阅）
   */
  function subscribe(destination: string, callback: (message: any) => void): any {
    if (!stompClient || connectionState.value !== 'connected') {
      console.warn('[WebSocket] 未连接，无法订阅')
      return null
    }
    return stompClient.subscribe(destination, (message: any) => {
      try {
        const body = JSON.parse(message.body)
        callback(body)
      } catch {
        callback(message.body)
      }
    })
  }

  /** 检查 WebSocket 是否可用 */
  function isAvailable(): boolean {
    return connectionState.value === 'connected'
  }

  onUnmounted(() => {
    disconnect()
  })

  return {
    connectionState,
    connect,
    disconnect,
    send,
    subscribe,
    isAvailable
  }
}

/**
 * 归一化 WebSocket 基地址（批次 10 · 任务 10.2.5）：
 * 去掉尾部斜杠，并保证最终地址以 `/ws` 结尾（后端端点路径）。
 *
 * 兼容两种前端配置写法：
 * - `VITE_WS_URL=http://localhost:9090`      → `http://localhost:9090/ws`
 * - `VITE_WS_URL=http://localhost:9090/ws`   → 原样使用（修复 `/ws/ws` 叠加）
 *
 * @param base 原始基地址
 * @returns 归一化后的端点地址
 */
export function normalizeWsUrl(base: string): string {
  const trimmed = (base || '').trim().replace(/\/+$/, '')
  if (!trimmed) {
    return '/ws'
  }
  return trimmed.endsWith('/ws') ? trimmed : `${trimmed}/ws`
}

/**
 * WebSocket 消息 → 用户可见通知的描述（批次 11 · R46）。
 *
 * <p>此前 `ChatView.vue` 调用了 `useWebSocket()` 却**没传** `onDocumentProgress` /
 * `onOpsAlert`：消息到了浏览器就被丢弃（通道已通、无人消费）。</p>
 *
 * <p>这里只做"判定 + 选文案键"（纯函数，可在 node 环境断言）——
 * 具体提示形式（ElMessage / ElNotification）与翻译交给调用方。</p>
 */
export interface WsNoticeDescriptor {
  /** 提示类型（喂给 el-message / el-notification 的 type） */
  type: 'success' | 'warning' | 'error' | 'info'
  /** i18n 键（调用方用 `t(key, params)` 翻译） */
  i18nKey: string
  /** i18n 插值参数 */
  params: Record<string, unknown>
}

/**
 * 文档处理进度 → 通知描述。
 *
 * <p>只对**终态**提示（READY / INDEXED / FAILED）：PARSING / CHUNKING / EMBEDDING
 * 这类中间态每步都弹会打扰用户，且知识库页面本就有轮询兜底。</p>
 *
 * @param payload 进度消息
 * @returns 通知描述；中间态返回 null（不打扰）
 */
export function describeDocumentProgress(payload: DocumentProgressMessage): WsNoticeDescriptor | null {
  const title = payload?.title || `#${payload?.documentId}`
  switch (payload?.status) {
    case 'READY':
      return {
        type: 'success',
        i18nKey: 'chat.ws.documentReady',
        params: { title, count: payload.chunkCount ?? 0 },
      }
    case 'INDEXED':
      // 部分就绪：关键词可搜、向量未就绪 —— 与知识库页的状态图例口径一致
      return { type: 'info', i18nKey: 'chat.ws.documentIndexed', params: { title } }
    case 'FAILED':
      return {
        type: 'error',
        i18nKey: 'chat.ws.documentFailed',
        params: { title, reason: payload.errorMsg || '' },
      }
    default:
      return null
  }
}

/**
 * 运维告警 → 通知描述（仅管理员会收到本频道的消息）。
 *
 * @param payload 告警消息
 * @returns 通知描述（CRITICAL → error，其余 → warning）
 */
export function describeOpsAlert(payload: OpsAlertMessage): WsNoticeDescriptor {
  return {
    type: payload?.level === 'CRITICAL' ? 'error' : 'warning',
    i18nKey: 'chat.ws.opsAlert',
    params: {
      level: payload?.level ?? 'UNKNOWN',
      alertType: payload?.alertType ?? '',
      message: payload?.message ?? '',
    },
  }
}

/**
 * 解析最终 WebSocket 端点地址（批次 11 · R36）。
 *
 * 取值优先级：显式 `brokerURL` → `VITE_WS_URL` → 同源缺省。
 *
 * `VITE_WS_URL` **已定义且为空串**时按"同源部署"处理（`/ws`）——
 * 这是 `.env.production` 的配置形态（与 `VITE_API_BASE_URL=` 留空同理）。
 * 此前这里写死 `http://localhost:9090` 兜底：生产包未定义该变量时会连访问者本机，
 * 且空串是 falsy，显式留空也会被兜底覆盖。
 *
 * @param brokerURL 调用方显式传入的地址（可选）
 * @param envWsUrl  `import.meta.env.VITE_WS_URL`（可为 undefined / 空串）
 * @returns 归一化后的端点地址（以 `/ws` 结尾；同源时为相对路径 `/ws`）
 */
export function resolveWsEndpoint(brokerURL?: string, envWsUrl?: string): string {
  return normalizeWsUrl(brokerURL ?? envWsUrl ?? '')
}

/**
 * 为握手地址附加 token 查询参数（浏览器无法自定义 WebSocket 请求头）。
 *
 * @param url 端点地址
 * @param token JWT
 * @returns 带 token 的地址
 */
export function appendToken(url: string, token: string): string {
  if (!token) {
    return url
  }
  const separator = url.includes('?') ? '&' : '?'
  return `${url}${separator}token=${encodeURIComponent(token)}`
}
