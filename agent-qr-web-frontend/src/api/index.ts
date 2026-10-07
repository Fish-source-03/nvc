import axios from 'axios'
import { ElMessage } from 'element-plus'
import {
  getAccessToken,
  getRefreshToken,
  setAccessToken,
  setRefreshToken,
  setTokenExpiresAt,
  removeAllTokens,
  getTokenExpiresAt,
} from '@/utils/token'

/**
 * ★ 批次 09 · 任务 9.4（问题 36）：API 基础地址与路径拼接的唯一口径。
 *
 * 生产环境 `VITE_API_BASE_URL` 为空（同源部署，反向代理把 `/api` 转发到后端），
 * 此时调用路径自带的 `/api` 前缀即为最终路径；
 * 开发环境为 `http://localhost:9090`。
 *
 * 修复前 `.env.production` 里配的是 `/api`，而 api/*.ts 的调用路径也以 `/api` 开头，
 * 拼接结果为 `/api/api/auth/login` → 生产环境接口全部 404。
 * 此前 SSE / refresh 用的是模板字符串（`${VITE_API_BASE_URL}/api/auth/refresh`），
 * 与 axios 的 baseURL 拼接是两套逻辑，改一处漏一处——现在统一走本函数。
 *
 * @param path 以 `/` 开头的接口路径（自带 `/api` 前缀，如 `/api/auth/refresh`）
 * @returns 可用的请求地址
 */
export function buildApiUrl(path: string): string {
  const base = resolveApiBaseUrl()
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  return base ? `${base}${normalizedPath}` : normalizedPath
}

/**
 * ★ 解析 API 基础地址（去掉尾部斜杠，避免与调用路径拼出双斜杠）。
 *
 * @returns 基础地址；生产环境为空串
 */
export function resolveApiBaseUrl(): string {
  return (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '')
}

const request = axios.create({
  baseURL: resolveApiBaseUrl(),
  timeout: 30000,
})

// ★ 是否正在刷新 Token
let isRefreshing = false
// ★ 等待刷新期间暂存的请求队列
let pendingRequests: Array<{
  resolve: (token: string) => void
  reject: (error: Error) => void
}> = []

// ★ 处理等待队列
function processQueue(error: Error | null, token?: string) {
  pendingRequests.forEach(({ resolve, reject }) => {
    if (error || !token) reject(error || new Error('Token 刷新失败'))
    else resolve(token)
  })
  pendingRequests = []
}

/** ★ 生成前端 TraceId（16位 hex） */
function generateTraceId(): string {
  const arr = new Uint8Array(8)
  crypto.getRandomValues(arr)
  return Array.from(arr, (b) => b.toString(16).padStart(2, '0')).join('')
}

// 请求拦截器：自动添加 Authorization 头 + TraceId
request.interceptors.request.use(
  (config) => {
    const token = getAccessToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    // ★ P2 新增 TraceId
    const traceId = generateTraceId()
    config.headers['X-Trace-Id'] = traceId
    return config
  },
  (error) => {
    return Promise.reject(error)
  },
)

/**
 * ★ 调用 Refresh Token 接口，静默刷新 Access Token
 * 使用队列机制保证并发请求只刷新一次
 */
async function handleTokenRefresh(failedConfig: any): Promise<any> {
  if (isRefreshing) {
    // 已有刷新请求在进行中 → 加入等待队列
    return new Promise((resolve, reject) => {
      pendingRequests.push({ resolve, reject })
    }).then((token) => {
      failedConfig.headers.Authorization = `Bearer ${token}`
      return request(failedConfig)
    })
  }

  isRefreshing = true
  try {
    const refreshTokenStr = getRefreshToken()
    const res = await axios.post(buildApiUrl('/api/auth/refresh'), { refreshToken: refreshTokenStr })
    const { accessToken, refreshToken, expiresIn } = res.data.data
    setAccessToken(accessToken)
    setRefreshToken(refreshToken)
    setTokenExpiresAt(Date.now() + expiresIn * 1000)

    processQueue(null, accessToken)

    failedConfig.headers.Authorization = `Bearer ${accessToken}`
    return request(failedConfig)
  } catch (error) {
    processQueue(error as Error)
    removeAllTokens()
    window.location.href = '/login'
    return Promise.reject(error)
  } finally {
    isRefreshing = false
  }
}

// ★ 响应拦截器 — P2 双 Token 静默刷新逻辑
request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code === 200) {
      return res
    }
    // 401: 不再直接跳登录，先尝试刷新 Token
    if (res.code === 401) {
      const refreshTokenValue = getRefreshToken()
      if (refreshTokenValue && !isRefreshing) {
        return handleTokenRefresh(response.config)
      }
      // 无可用的 refreshToken → 跳登录
      removeAllTokens()
      window.location.href = '/login'
      return Promise.reject(new Error(res.message || '未授权'))
    }
    // 403: 权限不足
    if (res.code === 403) {
      ElMessage.error('权限不足')
      return Promise.reject(new Error(res.message || '权限不足'))
    }
    // 500: 服务器错误
    if (res.code === 500) {
      ElMessage.error('服务器内部错误')
      return Promise.reject(new Error(res.message || '服务器内部错误'))
    }
    // 其他业务错误码
    ElMessage.error(res.message || '请求失败')
    return Promise.reject(new Error(res.message || '请求失败'))
  },
  (error) => {
    // SSE 流式请求错误特殊处理
    if (error.config?.responseType === 'stream') {
      return Promise.reject(error)
    }
    /**
     * ★ 批次 03 联动修复：HTTP 403（ABAC 越域、鉴权拒绝、匿名请求）→ 「权限不足」。
     * 此前非 2xx 一律提示「网络连接失败」，把权限问题伪装成网络问题；
     * 而 403 只在 HTTP 200 + 业务码 403 的分支里被识别，覆盖不到真正的 403 响应。
     * 其余状态码行为保持不变。
     */
    if (error.response?.status === 403) {
      ElMessage.error('权限不足')
      return Promise.reject(error)
    }
    ElMessage.error('网络连接失败')
    return Promise.reject(error)
  },
)

/**
 * ★ 供 SSE（fetchEventSource）等非 axios 请求使用：
 * 主动检查 token 有效期，过期或即将过期时静默刷新。
 * 复用 handleTokenRefresh 的队列机制，并发调用只刷新一次。
 *
 * @returns 有效的 accessToken，刷新失败时返回 null
 */
export async function ensureValidToken(): Promise<string | null> {
  const token = getAccessToken()
  if (!token) return null

  const expiresAt = getTokenExpiresAt()
  // 提前 60 秒刷新，避免刚好在请求过程中过期
  if (expiresAt && Date.now() < expiresAt - 60_000) {
    return token
  }

  // Token 即将过期或已过期 → 尝试刷新
  const refreshTokenStr = getRefreshToken()
  if (!refreshTokenStr) {
    // 无 refreshToken 且已过期 → 返回 null 触发重新登录
    if (expiresAt && Date.now() >= expiresAt) {
      removeAllTokens()
      window.location.href = '/login'
      return null
    }
    // expiresAt 异常（null）但 token 存在 → 保守原样返回
    return token
  }

  if (isRefreshing) {
    return new Promise((resolve, reject) => {
      pendingRequests.push({ resolve, reject })
    })
  }

  isRefreshing = true
  try {
    const res = await axios.post(buildApiUrl('/api/auth/refresh'), { refreshToken: refreshTokenStr })
    const { accessToken, refreshToken, expiresIn } = res.data.data
    setAccessToken(accessToken)
    setRefreshToken(refreshToken)
    setTokenExpiresAt(Date.now() + expiresIn * 1000)

    processQueue(null, accessToken)
    return accessToken
  } catch {
    processQueue(new Error('Token 刷新失败'))
    removeAllTokens()
    window.location.href = '/login'
    return null
  } finally {
    isRefreshing = false
  }
}

export default request
