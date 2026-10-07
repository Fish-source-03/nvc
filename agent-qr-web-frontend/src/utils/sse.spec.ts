// @vitest-environment node
import { describe, it, expect, afterEach, vi } from 'vitest'
import * as sseModule from './sse'
import { SSE_RETRY_DELAY_MS, shouldRetrySse } from './sse'
// 源码文本（Vite `?raw`）：用于"死代码不得回归"的源码级断言，无需 node 类型
import sseSource from './sse.ts?raw'
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

/**
 * 批次 11 · R50①：`createSSERequest` 死代码的防回归。
 *
 * 它是全仓无调用点的死代码，且 `onopen` 只有
 * `throw new Error('SSE 连接失败: HTTP ' + status)`——与 R19② 修复前的 403 缺口
 * 完全相同（fetchEventSource 不走 axios 拦截器，越域/未授权会被说成"连接异常"）。
 * 真实实现在 `src/api/chat.ts#askStream`（含 403/业务码分支与 token 预刷新），
 * 故删除它而不是接线；此处固化"不得回归"。
 */
describe('createSSERequest 死代码清理（批量 11 · R50①）', () => {
  it('★ 模块不再导出 createSSERequest（接线即回退 403 缺口，禁止回归）', () => {
    expect('createSSERequest' in sseModule).toBe(false)
  })

  it('★ sse.ts 不再自带 SSE 请求实现（唯一实现留在 api/chat.ts，含 403 处理）', () => {
    // 只断言"代码"，不禁止注释里提到它（R50① 的说明文字需要引用旧函数名）
    expect(sseSource).not.toContain('export function createSSERequest')
    expect(sseSource).not.toMatch(/\bfetchEventSource\s*\(/)
    expect(sseSource).not.toContain('@microsoft/fetch-event-source')
  })
})
