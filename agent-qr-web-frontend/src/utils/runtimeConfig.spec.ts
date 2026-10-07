// @vitest-environment node
import { describe, it, expect, afterEach, vi } from 'vitest'
import {
  DEFAULT_SSE_MAX_RECONNECT,
  DEFAULT_SSE_TIMEOUT,
  DEFAULT_TOKEN_REFRESH_AHEAD,
  isTokenExpiringSoon,
  parseEnvPositiveInt,
  resolveSseMaxReconnect,
  resolveSseTimeout,
  resolveTokenRefreshAhead,
} from './runtimeConfig'

/**
 * 前端运行时配置接线测试（批次 10 · 任务 10.5.2，问题 38）。
 *
 * 拦截的核心缺陷：`.env.development` 的 `VITE_SSE_TIMEOUT` / `VITE_TOKEN_REFRESH_AHEAD` /
 * `VITE_SSE_MAX_RECONNECT` 三个键**无任何引用**——SSE 无超时、无重连上限，
 * Token 提前刷新时间硬编码 `60_000`。本用例固化"改环境变量 → 取值随之变化"。
 */
describe('runtimeConfig · VITE_* 配置接线（batch-10）', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('未配置时使用默认值（5 分钟 / 60 秒 / 3 次）', () => {
    vi.stubEnv('VITE_SSE_TIMEOUT', '')
    vi.stubEnv('VITE_TOKEN_REFRESH_AHEAD', '')
    vi.stubEnv('VITE_SSE_MAX_RECONNECT', '')

    expect(resolveSseTimeout()).toBe(DEFAULT_SSE_TIMEOUT)
    expect(resolveTokenRefreshAhead()).toBe(DEFAULT_TOKEN_REFRESH_AHEAD)
    expect(resolveSseMaxReconnect()).toBe(DEFAULT_SSE_MAX_RECONNECT)
  })

  it('★ 修改 VITE_TOKEN_REFRESH_AHEAD 后提前刷新时间随之变化', () => {
    vi.stubEnv('VITE_TOKEN_REFRESH_AHEAD', '5000')
    expect(resolveTokenRefreshAhead()).toBe(5000)

    vi.stubEnv('VITE_TOKEN_REFRESH_AHEAD', '120000')
    expect(resolveTokenRefreshAhead()).toBe(120000)
  })

  it('★ 修改 VITE_SSE_TIMEOUT / VITE_SSE_MAX_RECONNECT 后取值随之变化', () => {
    vi.stubEnv('VITE_SSE_TIMEOUT', '30000')
    vi.stubEnv('VITE_SSE_MAX_RECONNECT', '0')
    expect(resolveSseTimeout()).toBe(30000)
    expect(resolveSseMaxReconnect()).toBe(0)
  })

  it('非法值回退默认（不产生 NaN 计时器）', () => {
    expect(parseEnvPositiveInt('abc', 42)).toBe(42)
    expect(parseEnvPositiveInt('-5', 42)).toBe(42)
    expect(parseEnvPositiveInt('12.7', 42)).toBe(12)
    expect(parseEnvPositiveInt(undefined, 42)).toBe(42)
  })

  it('★ isTokenExpiringSoon 使用配置的提前量判定', () => {
    const now = 1_000_000

    vi.stubEnv('VITE_TOKEN_REFRESH_AHEAD', '60000')
    // 距过期还有 59s < 提前量 60s → 需要刷新
    expect(isTokenExpiringSoon(now + 59_000, now)).toBe(true)
    // 距过期还有 61s > 提前量 60s → 不需要刷新
    expect(isTokenExpiringSoon(now + 61_000, now)).toBe(false)

    vi.stubEnv('VITE_TOKEN_REFRESH_AHEAD', '10000')
    // 提前量调小后，同样的 59s 不再触发刷新
    expect(isTokenExpiringSoon(now + 59_000, now)).toBe(false)
  })

  it('expiresAt 未知（null）时保守判定为需要刷新（与修复前行为一致）', () => {
    expect(isTokenExpiringSoon(null, 1_000_000)).toBe(true)
  })
})
