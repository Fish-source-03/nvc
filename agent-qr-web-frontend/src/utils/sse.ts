/**
 * SSE 重连决策与重连延迟常量（纯函数，便于用例固化"配置生效"）。
 *
 * ★ 批次 11 · R50①：本文件原有一个 `createSSERequest(options)` —— **死代码**
 *   （全仓无调用点，且其 `onopen` 只有 `throw new Error('SSE 连接失败: HTTP ' + status)`，
 *   与 R19② 修复前的 403 缺口完全相同：fetchEventSource 不走 axios 拦截器，
 *   越域/未授权会被提示成"连接异常"）。真实的 SSE 请求实现在
 *   `src/api/chat.ts#askStream`，它已带 403/业务码分支与 token 预刷新。
 *   两个实现并存只会留下一颗"接线即回退"的地雷，故**删除** createSSERequest；
 *   本文件只保留 chat.ts 实际引用的两个导出。
 */
/** 重连前的等待时间（毫秒） */
export const SSE_RETRY_DELAY_MS = 1_000

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
