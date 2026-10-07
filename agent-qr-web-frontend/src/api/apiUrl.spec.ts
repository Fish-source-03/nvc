// @vitest-environment node
import { describe, it, expect, vi, afterEach } from 'vitest'

vi.mock('element-plus', () => ({
  ElMessage: {
    error: vi.fn<() => void>(),
    success: vi.fn<() => void>(),
    warning: vi.fn<() => void>(),
    info: vi.fn<() => void>(),
  },
}))

// 以 ?raw 导入环境文件与源码（Vite 转换期读取，避免依赖 Node 内置模块与 tsconfig 的 node 类型）
import envProductionRaw from '../../.env.production?raw'
import apiIndexSource from './index.ts?raw'

import { buildApiUrl, resolveApiBaseUrl } from './index'

/**
 * 生产环境 API 路径双前缀修复测试（批次 09 · 任务 9.4，问题 36）。
 *
 * <p>缺陷：`.env.production` 的 `VITE_API_BASE_URL=/api` 与 api/*.ts 调用路径
 * 自带的 `/api` 前缀叠加 → `/api/api/auth/login`，生产环境接口全部 404；
 * 且 SSE / refresh 用的是模板字符串拼接（`${VITE_API_BASE_URL}/api/auth/refresh`），
 * 与 axios 的 baseURL 是两套逻辑。</p>
 *
 * <p>修复后统一走 `buildApiUrl()`，并把生产的基础地址留空
 * （同源部署，由反向代理转发 `/api`）。</p>
 */
/** 读取 .env.production 中某个键的值（未配置时返回 undefined） */
function productionEnvValue(key: string): string | undefined {
  const line = envProductionRaw
    .split(/\r?\n/)
    .find((l: string) => l.startsWith(`${key}=`))
  return line?.slice(key.length + 1).trim()
}

afterEach(() => {
  vi.unstubAllEnvs()
})

describe('buildApiUrl · 生产环境（VITE_API_BASE_URL 为空）', () => {
  it('★ 拼接结果为 /api/auth/login，不得出现 /api/api（问题 36 的原始缺陷）', () => {
    vi.stubEnv('VITE_API_BASE_URL', '')

    expect(buildApiUrl('/api/auth/login')).toBe('/api/auth/login')
    expect(buildApiUrl('/api/auth/login')).not.toContain('/api/api')
  })

  it('★ .env.production 中的 VITE_API_BASE_URL 必须为空（回归护栏）', () => {
    const value = productionEnvValue('VITE_API_BASE_URL')

    expect(value).toBe('')
  })

  it('★ 用 .env.production 的真实取值拼接，请求路径无双前缀', () => {
    vi.stubEnv('VITE_API_BASE_URL', productionEnvValue('VITE_API_BASE_URL') ?? '')

    // 所有 api/*.ts 的调用路径都自带 /api 前缀
    for (const path of [
      '/api/auth/login',
      '/api/auth/refresh',
      '/api/knowledge/documents',
      '/api/chat/ask',
      '/api/statistics/feedback/1',
    ]) {
      expect(buildApiUrl(path)).toBe(path)
      expect(buildApiUrl(path)).not.toContain('/api/api')
    }
  })

  it('以 /api 为基址（旧配置）会复现双前缀——证明修复点确实是"空基址"', () => {
    vi.stubEnv('VITE_API_BASE_URL', '/api')

    expect(buildApiUrl('/api/auth/login')).toBe('/api/api/auth/login')
  })
})

describe('buildApiUrl · 开发环境（VITE_API_BASE_URL=http://localhost:9090）', () => {
  it('★ 拼接结果为 http://localhost:9090/api/auth/login（开发行为不变）', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:9090')

    expect(buildApiUrl('/api/auth/login')).toBe('http://localhost:9090/api/auth/login')
    expect(buildApiUrl('/api/chat/ask/stream')).toBe('http://localhost:9090/api/chat/ask/stream')
  })

  it('基址尾部多斜杠时归一化，不产生 //', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:9090/')

    expect(buildApiUrl('/api/auth/login')).toBe('http://localhost:9090/api/auth/login')
    expect(resolveApiBaseUrl()).toBe('http://localhost:9090')
  })

  it('缺少前导 / 的路径会被补齐（拼接口径归一）', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:9090')

    expect(buildApiUrl('api/auth/login')).toBe('http://localhost:9090/api/auth/login')
  })
})

describe('SSE / refresh 的拼接方式与其他 API 统一', () => {
  it('★ index.ts 中不再有手工拼接 VITE_API_BASE_URL 的模板串', () => {
    expect(apiIndexSource).not.toMatch(/\$\{import\.meta\.env\.VITE_API_BASE_URL/)
  })

  it('★ refresh 使用 buildApiUrl 拼接（与 axios baseURL 同一口径）', () => {
    expect(apiIndexSource).toContain("buildApiUrl('/api/auth/refresh')")
    // 两处 refresh（响应拦截器 + ensureValidToken）都改到了
    expect(apiIndexSource.match(/buildApiUrl\('\/api\/auth\/refresh'\)/g)).toHaveLength(2)
  })
})
