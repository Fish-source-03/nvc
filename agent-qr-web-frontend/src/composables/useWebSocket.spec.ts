// @vitest-environment node
import { describe, it, expect, vi } from 'vitest'
import {
  DOCUMENT_PROGRESS_DESTINATION,
  OPS_ALERT_TOPIC,
  appendToken,
  describeDocumentProgress,
  describeOpsAlert,
  normalizeWsUrl,
  resolveWsEndpoint,
  type DocumentProgressMessage,
  type OpsAlertMessage,
} from './useWebSocket'
// 以原始文本导入环境文件与源码（vite ?raw）——静态回归护栏
import envProductionRaw from '../../.env.production?raw'
import wsSource from './useWebSocket.ts?raw'
import chatViewSource from '../views/chat/ChatView.vue?raw'
import zhLocaleSource from '../i18n/locales/zh-CN.ts?raw'
import enLocaleSource from '../i18n/locales/en-US.ts?raw'

/** 读取 .env.production 中某个键的值（未配置时返回 undefined） */
function productionEnvValue(key: string): string | undefined {
  const line = envProductionRaw
    .split(/\r?\n/)
    .find((l: string) => l.startsWith(`${key}=`))
  return line?.slice(key.length + 1).trim()
}

/**
 * WebSocket 端点地址与握手参数的纯函数测试（批次 10 · 任务 10.2.5，问题 34）。
 *
 * 背景：
 * - 后端端点固定为 `/ws`；`.env.development` 里 `VITE_WS_URL` 已经带 `/ws`，
 *   旧代码再拼一次会得到 `/ws/ws`（连接必然失败）；
 * - 后端 `/ws/**` 已从 `permitAll()` 收敛为 `authenticated()`，
 *   浏览器无法自定义握手请求头，因此令牌必须走查询参数。
 */
describe('useWebSocket · 端点与握手参数（batch-10）', () => {
  it('★ 基地址已带 /ws 时不重复叠加（修复 /ws/ws）', () => {
    expect(normalizeWsUrl('http://localhost:9090/ws')).toBe('http://localhost:9090/ws')
    expect(normalizeWsUrl('http://localhost:9090/ws/')).toBe('http://localhost:9090/ws')
  })

  it('★ 仅给主机地址时补上 /ws（后端端点路径）', () => {
    expect(normalizeWsUrl('http://localhost:9090')).toBe('http://localhost:9090/ws')
    expect(normalizeWsUrl('https://example.com/')).toBe('https://example.com/ws')
  })

  it('空值回退为同源路径 /ws', () => {
    expect(normalizeWsUrl('')).toBe('/ws')
    expect(normalizeWsUrl('   ')).toBe('/ws')
  })

  it('★ 握手令牌以查询参数传递（浏览器无法设置 WebSocket 请求头）', () => {
    const url = appendToken('http://localhost:9090/ws', 'abc.def.ghi')
    expect(url).toBe('http://localhost:9090/ws?token=abc.def.ghi')
    // 令牌需要 URL 编码（JWT 一般无特殊字符，但保持稳健）
    expect(appendToken('http://localhost:9090/ws', 'a+b/c=')).toContain('token=a%2Bb%2Fc%3D')
    // 已有查询参数时用 & 追加
    expect(appendToken('http://localhost:9090/ws?x=1', 't')).toBe('http://localhost:9090/ws?x=1&token=t')
    // 空令牌不加参数
    expect(appendToken('http://localhost:9090/ws', '')).toBe('http://localhost:9090/ws')
  })

  it('订阅目的地与后端约定一致（用户进度队列 / 运维告警频道）', () => {
    expect(DOCUMENT_PROGRESS_DESTINATION).toBe('/user/queue/documents/progress')
    expect(OPS_ALERT_TOPIC).toBe('/topic/ops.alerts')
  })
})

/**
 * 批次 11 · R36：生产包的 WebSocket 地址。
 *
 * <p>缺陷：`.env.production` 未定义 `VITE_WS_URL`，`useWebSocket()` 回退到写死的
 * `http://localhost:9090` —— 生产包会把用户浏览器指向访问者自己的本机 9090，
 * WebSocket 必然连不上（只剩 SSE 降级掩盖）。且空串是 falsy，
 * 即使把 `VITE_WS_URL=` 显式留空也会被兜底值覆盖。</p>
 */
describe('resolveWsEndpoint · 环境取值与兜底（batch-11 / R36）', () => {
  it('★ 环境变量显式留空（同源部署）→ 相对路径 /ws，绝不回退到 localhost', () => {
    expect(resolveWsEndpoint(undefined, '')).toBe('/ws')
    expect(resolveWsEndpoint(undefined, '   ')).toBe('/ws')
  })

  it('环境变量缺失 → 同源 /ws', () => {
    expect(resolveWsEndpoint(undefined, undefined)).toBe('/ws')
  })

  it('开发环境显式配置（已带 /ws）→ 原样使用，不叠加为 /ws/ws', () => {
    expect(resolveWsEndpoint(undefined, 'http://localhost:9090/ws')).toBe('http://localhost:9090/ws')
    expect(resolveWsEndpoint(undefined, 'http://localhost:9090')).toBe('http://localhost:9090/ws')
  })

  it('调用方显式传入的地址优先级最高（含空串 = 强制同源）', () => {
    expect(resolveWsEndpoint('http://a.example.com', 'http://b.example.com/ws')).toBe('http://a.example.com/ws')
    expect(resolveWsEndpoint('', 'http://b.example.com')).toBe('/ws')
  })

  it('★ 源码中不再写死 localhost 兜底（生产包不得指向访问者本机）', () => {
    // 只看代码行：注释里出现 localhost:9090 是文档（说明 dev 配置与本次修复）
    const codeOnly = wsSource
      .split(/\r?\n/)
      .filter((line: string) => !/^\s*(\/\/|\*|\/\*)/.test(line))
      .join('\n')

    expect(codeOnly).not.toContain('localhost:9090')
    // 兜底链必须是 resolveWsEndpoint（缺省 → 同源 /ws）
    expect(wsSource).toContain('resolveWsEndpoint(brokerURL, import.meta.env.VITE_WS_URL)')
  })

  it('★ .env.production 显式声明 VITE_WS_URL 且为空（同源，与 VITE_API_BASE_URL 同理）', () => {
    expect(productionEnvValue('VITE_WS_URL')).toBe('')
  })

  it('★ 用 .env.production 的真实取值解析 → 同源 /ws（生产包不含 localhost）', () => {
    const endpoint = resolveWsEndpoint(undefined, productionEnvValue('VITE_WS_URL') ?? '')

    expect(endpoint).toBe('/ws')
    expect(endpoint).not.toContain('localhost')
  })
})

/**
 * 批次 11 · R46：订阅了却无人消费。
 *
 * <p>批次 10.2 打通了服务端通道（文档进度按用户队列投递、运维告警广播给管理员），
 * 但 `ChatView.vue` 调用 `useWebSocket()` 时没传 `onDocumentProgress` / `onOpsAlert`，
 * 消息到浏览器后被静默丢弃。本组用例锁定"消息 → 用户可见提示"的判定逻辑。</p>
 */
describe('describeDocumentProgress · 文档进度提示（batch-11 / R46）', () => {
  function progress(partial: Partial<DocumentProgressMessage>): DocumentProgressMessage {
    return { type: 'DOCUMENT_PROGRESS', documentId: 42, status: 'READY', ...partial }
  }

  it('★ READY → 成功提示（带标题与切片数）', () => {
    const notice = describeDocumentProgress(progress({ status: 'READY', title: '考勤制度.pdf', chunkCount: 12 }))

    expect(notice).toEqual({
      type: 'success',
      i18nKey: 'chat.ws.documentReady',
      params: { title: '考勤制度.pdf', count: 12 },
    })
  })

  it('★ INDEXED → 部分就绪提示（关键词可搜、语义未就绪，与知识库图例口径一致）', () => {
    const notice = describeDocumentProgress(progress({ status: 'INDEXED', title: '报销流程.docx' }))

    expect(notice?.type).toBe('info')
    expect(notice?.i18nKey).toBe('chat.ws.documentIndexed')
  })

  it('★ FAILED → 错误提示并带上后端错误信息', () => {
    const notice = describeDocumentProgress(
      progress({ status: 'FAILED', title: '损坏文件.pdf', errorMsg: '解析失败：文件已损坏' }),
    )

    expect(notice?.type).toBe('error')
    expect(notice?.i18nKey).toBe('chat.ws.documentFailed')
    expect(notice?.params.reason).toBe('解析失败：文件已损坏')
  })

  it('中间态（UPLOADED/PARSING/CHUNKING/EMBEDDING/DELETING）→ 不提示（避免每步都弹）', () => {
    for (const status of ['UPLOADED', 'PARSING', 'CHUNKING', 'EMBEDDING', 'DELETING', 'WHATEVER']) {
      expect(describeDocumentProgress(progress({ status }))).toBeNull()
    }
  })

  it('标题缺失时回退到 #文档ID；切片数缺失按 0 处理', () => {
    const notice = describeDocumentProgress(progress({ title: undefined, chunkCount: undefined }))

    expect(notice?.params.title).toBe('#42')
    expect(notice?.params.count).toBe(0)
  })
})

describe('describeOpsAlert · 运维告警提示（batch-11 / R46）', () => {
  function alert(partial: Partial<OpsAlertMessage>): OpsAlertMessage {
    return { type: 'OPS_ALERT', level: 'WARNING', alertType: 'DLQ_BACKLOG', message: '死信队列积压', ...partial }
  }

  it('★ CRITICAL → error 级提示', () => {
    const notice = describeOpsAlert(alert({ level: 'CRITICAL', alertType: 'DATASOURCE_SYNC_FAILED' }))

    expect(notice.type).toBe('error')
    expect(notice.i18nKey).toBe('chat.ws.opsAlert')
    expect(notice.params).toEqual({
      level: 'CRITICAL',
      alertType: 'DATASOURCE_SYNC_FAILED',
      message: '死信队列积压',
    })
  })

  it('WARNING（及其他级别）→ warning 级提示', () => {
    expect(describeOpsAlert(alert({ level: 'WARNING' })).type).toBe('warning')
    expect(describeOpsAlert(alert({ level: undefined as unknown as 'WARNING' })).type).toBe('warning')
  })

  it('字段缺失时不抛异常（脏消息不阻断前端）', () => {
    const notice = describeOpsAlert({ type: 'OPS_ALERT' } as OpsAlertMessage)

    expect(notice.params.level).toBe('UNKNOWN')
    expect(notice.params.message).toBe('')
  })
})

describe('ChatView.vue · WebSocket 消费接线护栏（batch-11 / R46）', () => {
  it('★ 传入两个回调 + 管理员订阅开关（不再"订阅了却丢弃消息"）', () => {
    expect(chatViewSource).toContain('onDocumentProgress: handleDocumentProgress')
    expect(chatViewSource).toContain('onOpsAlert: handleOpsAlert')
    expect(chatViewSource).toContain('subscribeOpsAlerts: authStore.isAdmin')
  })

  it('★ 文档进度用 ElMessage、运维告警用常驻 ElNotification', () => {
    expect(chatViewSource).toContain('describeDocumentProgress(payload)')
    expect(chatViewSource).toContain('describeOpsAlert(payload)')
    expect(chatViewSource).toContain('ElNotification')
    // 运维告警不自动消失（需人工确认）
    expect(chatViewSource).toMatch(/duration:\s*0/)
  })

  it('★ 中英文语言包都定义了 chat.ws.* 文案（缺 key 会渲染成 key 路径）', () => {
    for (const source of [zhLocaleSource, enLocaleSource]) {
      expect(source).toContain('ws: {')
      for (const key of ['documentReady', 'documentIndexed', 'documentFailed', 'opsAlertTitle', 'opsAlert']) {
        expect(source).toContain(`${key}:`)
      }
    }
  })

  it('★ 语言包能被 vue-i18n 真实解析并插值（不是"看起来有 key"）', async () => {
    // i18n 模块在导入期读取 localStorage 的 locale，须先打桩再用动态导入
    const storage = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => void storage.set(key, String(value)),
      removeItem: (key: string) => void storage.delete(key),
      clear: () => void storage.clear(),
    })

    try {
      const { default: i18n } = await import('../i18n/index')
      const { t, locale } = i18n.global

      locale.value = 'zh-CN'
      const zh = t('chat.ws.documentReady', { title: '考勤制度.pdf', count: 12 })
      expect(zh).toContain('考勤制度.pdf')
      expect(zh).toContain('12')
      expect(zh).not.toContain('chat.ws')

      locale.value = 'en-US'
      const en = t('chat.ws.documentFailed', { title: 'broken.pdf', reason: 'parse error' })
      expect(en).toContain('broken.pdf')
      expect(en).toContain('parse error')
      expect(en).not.toContain('chat.ws')

      locale.value = 'zh-CN'
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
