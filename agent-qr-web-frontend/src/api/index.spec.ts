// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest'

const { errorSpy } = vi.hoisted(() => ({ errorSpy: vi.fn<(message: string) => void>() }))

vi.mock('element-plus', () => ({
  ElMessage: {
    error: errorSpy,
    success: vi.fn<() => void>(),
    warning: vi.fn<() => void>(),
    info: vi.fn<() => void>(),
  },
}))

import request from './index'

/**
 * 批次 03 前端联动修复 —— axios 响应拦截器的 HTTP 403 分支。
 *
 * <p>后端 403 现为统一 Result 结构（越域 / 鉴权拒绝），匿名请求为 403 空 body。
 * 修复前非 2xx 一律提示「网络连接失败」，把权限问题伪装成网络故障。</p>
 */
type RejectedHandler = (error: unknown) => Promise<unknown>

function rejectedHandler(): RejectedHandler {
  const handlers = request.interceptors.response.handlers
  const rejected = handlers?.[0]?.rejected
  if (!rejected) throw new Error('响应拦截器未注册 rejected 处理器')
  return rejected as RejectedHandler
}

describe('axios response interceptor · HTTP 403 (batch-03)', () => {
  beforeEach(() => {
    errorSpy.mockClear()
  })

  it('HTTP 403 → 提示「权限不足」而非「网络连接失败」', async () => {
    const error = { response: { status: 403, data: { code: 403, message: '权限不足' } }, config: {} }
    await expect(rejectedHandler()(error)).rejects.toBe(error)
    expect(errorSpy).toHaveBeenCalledTimes(1)
    expect(errorSpy).toHaveBeenCalledWith('权限不足')
  })

  it('匿名请求的 403 空 body → 同样提示「权限不足」', async () => {
    const error = { response: { status: 403, data: '' }, config: {} }
    await expect(rejectedHandler()(error)).rejects.toBe(error)
    expect(errorSpy).toHaveBeenCalledWith('权限不足')
  })

  it('其余 HTTP 状态码行为不变 → 仍提示「网络连接失败」', async () => {
    for (const status of [400, 401, 404, 422, 500, 502, 503]) {
      errorSpy.mockClear()
      const error = { response: { status }, config: {} }
      await expect(rejectedHandler()(error)).rejects.toBe(error)
      expect(errorSpy).toHaveBeenCalledTimes(1)
      expect(errorSpy).toHaveBeenCalledWith('网络连接失败')
    }
  })

  it('无 response（网络层错误 / 超时）行为不变 → 仍提示「网络连接失败」', async () => {
    const error = { config: {}, message: 'Network Error' }
    await expect(rejectedHandler()(error)).rejects.toBe(error)
    expect(errorSpy).toHaveBeenCalledWith('网络连接失败')
  })

  it('responseType=stream 的 SSE 既有行为不变 → 静默 reject，不弹提示', async () => {
    const error = { response: { status: 403 }, config: { responseType: 'stream' } }
    await expect(rejectedHandler()(error)).rejects.toBe(error)
    expect(errorSpy).not.toHaveBeenCalled()
  })

  it('原始 error 对象被原样 reject，保留 error.response 供调用方判断', async () => {
    const error = { response: { status: 403 }, config: {}, message: 'Forbidden' }
    await expect(rejectedHandler()(error)).rejects.toMatchObject({
      response: { status: 403 },
      message: 'Forbidden',
    })
  })
})
