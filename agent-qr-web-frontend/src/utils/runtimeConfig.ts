/**
 * 前端运行时配置解析（批次 10 · 任务 10.5.2，问题 38）。
 *
 * 背景：`.env.development` 中的三个键（`VITE_SSE_TIMEOUT` / `VITE_TOKEN_REFRESH_AHEAD` /
 * `VITE_SSE_MAX_RECONNECT`）此前**无任何引用**——SSE 没有超时、没有重连上限，
 * Token 提前刷新时间硬编码为 `60_000`（`api/index.ts`）——典型的"改配置没反应"的死配置。
 *
 * 设计要点：
 * 1. **读取发生在调用时**（而非模块加载时）——这样 `vi.stubEnv()` 能在用例中改变取值，
 *    也让"改环境变量 → 行为变化"这条链路可被自动化测试固化；
 * 2. **非法/缺失值一律回退默认值**，不抛异常、不产生 NaN 计时器。
 */

/** SSE 流式超时默认值（毫秒，5 分钟）——与后端 SseEmitter 的 5 分钟超时一致 */
export const DEFAULT_SSE_TIMEOUT = 300_000

/** Token 提前刷新时间默认值（毫秒，提前 60 秒） */
export const DEFAULT_TOKEN_REFRESH_AHEAD = 60_000

/** SSE 最大重连次数默认值 */
export const DEFAULT_SSE_MAX_RECONNECT = 3

/**
 * 解析环境变量中的非负整数。
 *
 * @param raw 原始值（`import.meta.env` 取出的字符串，可能为 undefined）
 * @param fallback 解析失败时的回退值
 * @returns 解析结果
 */
export function parseEnvPositiveInt(raw: unknown, fallback: number): number {
  if (raw === undefined || raw === null || raw === '') {
    return fallback
  }
  const value = Number(raw)
  return Number.isFinite(value) && value >= 0 ? Math.floor(value) : fallback
}

/**
 * SSE 流式超时（毫秒）。环境变量：`VITE_SSE_TIMEOUT`。
 *
 * @returns 超时毫秒数
 */
export function resolveSseTimeout(): number {
  return parseEnvPositiveInt(import.meta.env.VITE_SSE_TIMEOUT, DEFAULT_SSE_TIMEOUT)
}

/**
 * Token 提前刷新时间（毫秒）。环境变量：`VITE_TOKEN_REFRESH_AHEAD`。
 *
 * @returns 提前量毫秒数
 */
export function resolveTokenRefreshAhead(): number {
  return parseEnvPositiveInt(import.meta.env.VITE_TOKEN_REFRESH_AHEAD, DEFAULT_TOKEN_REFRESH_AHEAD)
}

/**
 * SSE 最大重连次数。环境变量：`VITE_SSE_MAX_RECONNECT`。
 *
 * @returns 最大重连次数
 */
export function resolveSseMaxReconnect(): number {
  return parseEnvPositiveInt(import.meta.env.VITE_SSE_MAX_RECONNECT, DEFAULT_SSE_MAX_RECONNECT)
}

/**
 * 判断 Access Token 是否已进入"应提前刷新"窗口。
 *
 * @param expiresAt Token 过期时间戳（毫秒）；null 表示未知
 * @param now 当前时间戳（毫秒，默认取系统时间；便于用例注入）
 * @returns true 表示需要刷新
 */
export function isTokenExpiringSoon(expiresAt: number | null, now: number = Date.now()): boolean {
  if (expiresAt === null || expiresAt === undefined) {
    return true
  }
  return now >= expiresAt - resolveTokenRefreshAhead()
}
