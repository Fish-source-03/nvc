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

import { chatApi } from './chat'
import { SSE_RETRY_DELAY_MS } from '@/utils/sse'

const storage = new Map<string, string>()

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
