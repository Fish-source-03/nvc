// @vitest-environment node
import { describe, it, expect, afterEach, vi } from 'vitest'
import { SSE_RETRY_DELAY_MS, shouldRetrySse } from './sse'
import { resolveSseMaxReconnect } from './runtimeConfig'

/**
 * SSE 重连决策测试（批次 10 · 任务 10.5.2，问题 38）。
 *
 * 接线前 `VITE_SSE_MAX_RECONNECT` 无任何引用（SSE 无重连配置）；
 * 接线后重连次数上限来自该配置，且**仅在尚未收到任何消息时**才重试
 * （已开始输出的流重试会造成前端重复追加内容）。
 */
describe('shouldRetrySse · 重连决策（batch-10）', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('★ 未收到任何消息且未达上限 → 允许重连', () => {
    expect(
      shouldRetrySse({ receivedAnyMessage: false, aborted: false, attempts: 1, maxReconnect: 3 }),
    ).toBe(true)
  })

  it('★ 已达到 VITE_SSE_MAX_RECONNECT 上限 → 不再重连', () => {
    expect(
      shouldRetrySse({ receivedAnyMessage: false, aborted: false, attempts: 4, maxReconnect: 3 }),
    ).toBe(false)

    vi.stubEnv('VITE_SSE_MAX_RECONNECT', '1')
    const maxReconnect = resolveSseMaxReconnect()
    expect(
      shouldRetrySse({ receivedAnyMessage: false, aborted: false, attempts: 1, maxReconnect }),
    ).toBe(true)
    expect(
      shouldRetrySse({ receivedAnyMessage: false, aborted: false, attempts: 2, maxReconnect }),
    ).toBe(false)
  })

  it('★ 已收到消息 → 不重连（避免重复输出）', () => {
    expect(
      shouldRetrySse({ receivedAnyMessage: true, aborted: false, attempts: 1, maxReconnect: 3 }),
    ).toBe(false)
  })

  it('调用方主动取消 / 超时中止 → 不重连', () => {
    expect(
      shouldRetrySse({ receivedAnyMessage: false, aborted: true, attempts: 1, maxReconnect: 3 }),
    ).toBe(false)
  })

  it('重连延迟为常量（毫秒）', () => {
    expect(SSE_RETRY_DELAY_MS).toBeGreaterThan(0)
  })
})
