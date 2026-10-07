// @vitest-environment node
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

/**
 * SSE 流式问答的接线测试（批次 10 · 任务 10.5.2，问题 38）。
 *
 * 接线前：`VITE_SSE_TIMEOUT` / `VITE_SSE_MAX_RECONNECT` 无任何引用——实际 SSE 路径
 * 既没有超时保护，也没有重连上限。本用例通过 mock `@microsoft/fetch-event-source`
 * 直接驱动 `askStream` 的 onmessage/onerror，验证：
 * - 未收到消息时的失败会按 `VITE_SSE_MAX_RECONNECT` 重连；
 * - 已收到 token 后失败不再重连（避免重复输出）；
 * - 超过 `VITE_SSE_TIMEOUT` 中止并提示超时。
 */

const { fetchEventSourceMock } = vi.hoisted(() => ({
  fetchEventSourceMock: vi.fn<(url: string, options: any) => void>(),
}))

vi.mock('@microsoft/fetch-event-source', () => ({
  fetchEventSource: fetchEventSourceMock,
  EventStreamContentType: 'text/event-stream',
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    error: vi.fn<() => void>(),
    success: vi.fn<() => void>(),
    warning: vi.fn<() => void>(),
    info: vi.fn<() => void>(),
  },
}))

import { chatApi, describeSseHttpFailure } from './chat'
import request from './index'
import { SSE_RETRY_DELAY_MS } from '@/utils/sse'
// 以原始文本导入源码（vite ?raw），用于"必需参数"等源码级护栏
import chatSource from './chat.ts?raw'

const storage = new Map<string, string>()

/** 构造一个只带 status / ok / headers 的响应替身（fetchEventSource 的 onopen 入参契约） */
function fakeResponse(status: number, contentType = ''): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(contentType ? { 'content-type': contentType } : {}),
  } as unknown as Response
}

function installStorage() {
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, String(value)),
    removeItem: (key: string) => storage.delete(key),
    clear: () => storage.clear(),
  })
}

function callbacksSpy() {
  return {
    onToken: vi.fn<(token: string) => void>(),
    onDone: vi.fn<(data: unknown) => void>(),
    onError: vi.fn<(error: string) => void>(),
  }
}

describe('chatApi.askStream · SSE 超时与重连接线（batch-10）', () => {
  beforeEach(() => {
    fetchEventSourceMock.mockClear()
    storage.clear()
    installStorage()
    storage.set('access_token', 'test-jwt')
    storage.set('token_expires_at', String(Date.now() + 3_600_000))
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
  })

  it('★ 未收到任何消息时的连接失败 → 按 VITE_SSE_MAX_RECONNECT 重连', async () => {
    vi.stubEnv('VITE_SSE_MAX_RECONNECT', '2')
    const callbacks = callbacksSpy()
    chatApi.askStream('问题', 'HR', null, callbacks)
    await vi.advanceTimersByTimeAsync(0)

    expect(fetchEventSourceMock).toHaveBeenCalledTimes(1)
    fetchEventSourceMock.mock.calls[0]![1].onerror(new Error('network down'))
    await vi.advanceTimersByTimeAsync(SSE_RETRY_DELAY_MS)

    expect(fetchEventSourceMock).toHaveBeenCalledTimes(2)
    expect(callbacks.onError).not.toHaveBeenCalled()
  })

  it('★ 超过重连上限后 → 中止并提示「连接异常，请重试」', async () => {
    vi.stubEnv('VITE_SSE_MAX_RECONNECT', '1')
    const callbacks = callbacksSpy()
    chatApi.askStream('问题', 'HR', null, callbacks)
    await vi.advanceTimersByTimeAsync(0)

    fetchEventSourceMock.mock.calls[0]![1].onerror(new Error('boom'))
    await vi.advanceTimersByTimeAsync(SSE_RETRY_DELAY_MS)
    expect(fetchEventSourceMock).toHaveBeenCalledTimes(2)

    fetchEventSourceMock.mock.calls[1]![1].onerror(new Error('boom again'))
    await vi.advanceTimersByTimeAsync(SSE_RETRY_DELAY_MS)

    expect(fetchEventSourceMock).toHaveBeenCalledTimes(2)
    expect(callbacks.onError).toHaveBeenCalledWith('连接异常，请重试')
  })

  it('★ 已收到 token 后失败 → 不再重连（避免重复追加内容）', async () => {
    vi.stubEnv('VITE_SSE_MAX_RECONNECT', '3')
    const callbacks = callbacksSpy()
    chatApi.askStream('问题', 'HR', null, callbacks)
    await vi.advanceTimersByTimeAsync(0)

    const options = fetchEventSourceMock.mock.calls[0]![1]
    options.onmessage({ event: 'token', data: '你好' })
    expect(callbacks.onToken).toHaveBeenCalledWith('你好')

    options.onerror(new Error('mid-stream failure'))
    await vi.advanceTimersByTimeAsync(SSE_RETRY_DELAY_MS * 3)

    expect(fetchEventSourceMock).toHaveBeenCalledTimes(1)
    expect(callbacks.onError).toHaveBeenCalledWith('连接异常，请重试')
  })

  it('★ 超过 VITE_SSE_TIMEOUT → 中止请求并提示超时', async () => {
    vi.stubEnv('VITE_SSE_TIMEOUT', '50')
    const callbacks = callbacksSpy()
    chatApi.askStream('问题', 'HR', null, callbacks)
    await vi.advanceTimersByTimeAsync(0)

    expect(fetchEventSourceMock).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(60)

    expect(callbacks.onError).toHaveBeenCalledWith('响应超时（50ms）')
  })

  it('正常完成（done）后不触发任何错误回调', async () => {
    const callbacks = callbacksSpy()
    chatApi.askStream('问题', 'HR', null, callbacks)
    await vi.advanceTimersByTimeAsync(0)

    const options = fetchEventSourceMock.mock.calls[0]![1]
    options.onmessage({
      event: 'done',
      data: JSON.stringify({ answer: '答案', conversationId: 1, messageId: 2, sources: [] }),
    })
    options.onclose()
    await vi.advanceTimersByTimeAsync(1000)

    expect(callbacks.onDone).toHaveBeenCalled()
    expect(callbacks.onError).not.toHaveBeenCalled()
    expect(fetchEventSourceMock).toHaveBeenCalledTimes(1)
  })
})

/**
 * 批次 11 · R19①：`chatApi.ask()` 的 domain 缺口。
 *
 * 缺陷：`/api/chat/ask`（同步问答）与流式接口一样强制校验 domain，
 * 而该方法没有 domain 参数（全仓亦无调用点）——一旦启用必然失败。
 * 修复：把 domain 提为**必需**参数，约束前移到编译期。
 */
describe('chatApi.ask · 同步问答的 domain 契约（batch-11 / R19①）', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('★ 请求体携带 domain（与后端强制校验的字段一致）', () => {
    const postSpy = vi.spyOn(request, 'post').mockResolvedValue({} as never)

    chatApi.ask('今年的考勤规则是什么？', 'HR', 7)

    expect(postSpy).toHaveBeenCalledWith('/api/chat/ask', {
      query: '今年的考勤规则是什么？',
      domain: 'HR',
      conversationId: 7,
    })
  })

  it('不带会话 ID 时 conversationId 原样透传（新会话场景）', () => {
    const postSpy = vi.spyOn(request, 'post').mockResolvedValue({} as never)

    chatApi.ask('问题', 'COMMON')

    expect(postSpy).toHaveBeenCalledWith('/api/chat/ask', {
      query: '问题',
      domain: 'COMMON',
      conversationId: undefined,
    })
  })

  it('★ 源码中 domain 是必需参数（不是 `domain?:`，避免再次出现"启用必失败"的死路径）', () => {
    expect(chatSource).toMatch(/ask\(query: string, domain: string, conversationId\?: number\)/)
    expect(chatSource).not.toMatch(/ask\(query: string, conversationId\?: number\)/)
  })
})

/**
 * 批次 11 · R19②：SSE 的 403/400 提示缺口。
 *
 * 缺陷：`fetchEventSource` 不走 axios 实例，拿不到响应拦截器的 403 分支，
 * 越域（403）时用户看到的是「连接异常，请重试」，还会无意义地重连。
 */
describe('chatApi.askStream · 建连被拒绝的精准文案（batch-11 / R19②）', () => {
  beforeEach(() => {
    fetchEventSourceMock.mockClear()
    storage.clear()
    installStorage()
    storage.set('access_token', 'test-jwt')
    storage.set('token_expires_at', String(Date.now() + 3_600_000))
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
  })

  it('describeSseHttpFailure：403/401/400 给出精准文案，其余状态码返回 null（可重试故障）', () => {
    expect(describeSseHttpFailure(403)).toContain('权限不足')
    expect(describeSseHttpFailure(401)).toContain('登录已过期')
    expect(describeSseHttpFailure(400)).toContain('业务域')
    // 「连接异常」只在真正可重试的故障上出现
    for (const status of [200, 204, 429, 500, 502, 503]) {
      expect(describeSseHttpFailure(status)).toBeNull()
    }
  })

  it('★ 建连 403 → 提示「权限不足」且不再重连（越域不是"再试一次"能解决的）', async () => {
    vi.stubEnv('VITE_SSE_MAX_RECONNECT', '3')
    const callbacks = callbacksSpy()
    chatApi.askStream('问题', 'FINANCE', null, callbacks)
    await vi.advanceTimersByTimeAsync(0)

    const options = fetchEventSourceMock.mock.calls[0]![1]
    await expect(options.onopen(fakeResponse(403))).rejects.toThrow('SSE 建连被拒绝')
    options.onerror(new Error('HTTP 403'))
    await vi.advanceTimersByTimeAsync(SSE_RETRY_DELAY_MS * 5)

    expect(fetchEventSourceMock).toHaveBeenCalledTimes(1)
    expect(callbacks.onError).toHaveBeenCalledTimes(1)
    expect(callbacks.onError.mock.calls[0]![0]).toContain('权限不足')
  })

  it('★ HTTP 200 但不是事件流（后端以 JSON 返回业务码 400，如 domain 缺失）→ 精准文案且不重连', async () => {
    vi.stubEnv('VITE_SSE_MAX_RECONNECT', '3')
    const callbacks = callbacksSpy()
    chatApi.askStream('问题', 'HR', null, callbacks)
    await vi.advanceTimersByTimeAsync(0)

    const options = fetchEventSourceMock.mock.calls[0]![1]
    await expect(options.onopen(fakeResponse(200, 'application/json'))).rejects.toThrow('不是事件流')
    options.onerror(new Error('not an event stream'))
    await vi.advanceTimersByTimeAsync(SSE_RETRY_DELAY_MS * 5)

    expect(fetchEventSourceMock).toHaveBeenCalledTimes(1)
    expect(callbacks.onError).toHaveBeenCalledTimes(1)
    expect(callbacks.onError.mock.calls[0]![0]).toContain('业务域')
  })

  it('5xx 建连失败 → 沿用可重试语义（仍按 VITE_SSE_MAX_RECONNECT 重连）', async () => {
    vi.stubEnv('VITE_SSE_MAX_RECONNECT', '2')
    const callbacks = callbacksSpy()
    chatApi.askStream('问题', 'HR', null, callbacks)
    await vi.advanceTimersByTimeAsync(0)

    const options = fetchEventSourceMock.mock.calls[0]![1]
    await expect(options.onopen(fakeResponse(503))).rejects.toThrow('SSE 建连失败')
    options.onerror(new Error('HTTP 503'))
    await vi.advanceTimersByTimeAsync(SSE_RETRY_DELAY_MS)

    expect(fetchEventSourceMock).toHaveBeenCalledTimes(2)
    expect(callbacks.onError).not.toHaveBeenCalled()
  })

  it('正常事件流响应（200 + text/event-stream）不会误判为拒绝', async () => {
    const callbacks = callbacksSpy()
    chatApi.askStream('问题', 'HR', null, callbacks)
    await vi.advanceTimersByTimeAsync(0)

    const options = fetchEventSourceMock.mock.calls[0]![1]
    await expect(
      options.onopen(fakeResponse(200, 'text/event-stream; charset=utf-8')),
    ).resolves.toBeUndefined()
    expect(callbacks.onError).not.toHaveBeenCalled()
  })
})
