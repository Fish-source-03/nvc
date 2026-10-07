// @vitest-environment node
import { describe, it, expect } from 'vitest'
import {
  DOCUMENT_PROGRESS_DESTINATION,
  OPS_ALERT_TOPIC,
  appendToken,
  normalizeWsUrl,
} from './useWebSocket'

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
