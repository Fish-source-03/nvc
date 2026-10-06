import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { useRouter } from 'vue-router'
import { authApi } from '@/api/auth'
import {
  getAccessToken,
  setAccessToken,
  getRefreshToken,
  setRefreshToken,
  getTokenExpiresAt,
  setTokenExpiresAt,
  removeAllTokens,
  getUserFromStorage,
  setUserToStorage,
} from '@/utils/token'
import { parseAllowedDomains } from '@/utils/format'
import { DOMAINS } from '@/types'

// ★ P2 ABAC 扩展用户主体
export interface UserPrincipal {
  id: number
  username: string
  realName: string
  role: string
  email: string
  phone: string
  // ★ P2 ABAC 字段
  department: string
  clearanceLevel: number
  allowedDomains: string[]
  title: string
}

// ==================== 域列表纯函数（批次 03 前端联动修复） ====================
/**
 * 归一化域列表：去空、去空白、去重（保持原顺序）。
 * 后端 `allowed_domains` 为逗号分隔字符串，登录/刷新用户信息时经
 * {@link parseAllowedDomains} 解析，仍可能含空串（如 "HR,,RD"）或重复项。
 */
export function normalizeDomains(allowedDomains: string[] | null | undefined): string[] {
  if (!allowedDomains || allowedDomains.length === 0) return []
  const result: string[] = []
  for (const raw of allowedDomains) {
    const domain = typeof raw === 'string' ? raw.trim() : ''
    if (domain && !result.includes(domain)) result.push(domain)
  }
  return result
}

/**
 * 解析用户实际可选的业务域列表。
 *
 * <p>批次 03 后 `/api/chat/ask` 与 `/api/chat/ask/stream` 强制校验 `domain`：
 * 缺失 → 业务码 400；越域 → 403。因此下拉框不再提供「全部域」（空值）选项。</p>
 *
 * <p>admin 的 `allowed_domains` 理论上覆盖全部域，但存在为空的历史数据；
 * 后端 ABAC 对 admin 是直通的（`canQueryDomain` 直接放行），所以 admin
 * 列表为空时回退到内置全量 {@link DOMAINS}，避免全权限账号反而无法提问。</p>
 *
 * @param allowedDomains 当前用户已授权的域（`user.allowedDomains`）
 * @param isAdmin        当前用户是否为管理员
 * @returns 可选的域列表；普通用户无授权域时返回空数组
 */
export function resolveAvailableDomains(
  allowedDomains: string[] | null | undefined,
  isAdmin = false,
): string[] {
  const owned = normalizeDomains(allowedDomains)
  if (owned.length > 0) return owned
  return isAdmin ? [...DOMAINS] : []
}

/**
 * 挑选域选择器的默认选中项 —— 即"首个可用域"。
 *
 * @param allowedDomains 当前用户已授权的域
 * @param isAdmin        当前用户是否为管理员
 * @returns 默认域；普通用户无任何可用域时返回 `null`（调用方应禁用发送并提示）
 */
export function pickDefaultDomain(
  allowedDomains: string[] | null | undefined,
  isAdmin = false,
): string | null {
  return resolveAvailableDomains(allowedDomains, isAdmin)[0] ?? null
}

export const useAuthStore = defineStore('auth', () => {
  const router = useRouter()

  // State
  const accessToken = ref<string | null>(getAccessToken())
  const refreshToken = ref<string | null>(getRefreshToken())
  const tokenExpiresAt = ref<number | null>(getTokenExpiresAt())
  const user = ref<UserPrincipal | null>(getUserFromStorage() as UserPrincipal | null)

  // Getters
  const isLoggedIn = computed(() => !!accessToken.value)
  const isAdmin = computed(() => user.value?.role === 'admin')

  // ★ P2 ABAC Getters
  function hasDomain(domain: string): boolean {
    return user.value?.allowedDomains?.includes(domain) ?? false
  }

  function hasClearance(level: number): boolean {
    return (user.value?.clearanceLevel ?? 0) >= level
  }

  function isManager(): boolean {
    return user.value?.title === 'manager'
  }

  function isDirector(): boolean {
    return user.value?.title === 'director'
  }

  /** ★ P2: 能否查看数据仪表盘 — 仅总监 + 绝密 */
  const canViewDashboard = computed(() =>
    user.value?.title === 'director' && user.value?.clearanceLevel === 3
  )

  /** ★ P2: 能否进入用户管理 — 职级>=经理 且 密级>=机密 */
  const canManageUsers = computed(() => {
    if (!user.value) return false
    const titleLevel = { employee: 1, manager: 2, director: 3 }[user.value.title] || 0
    return titleLevel >= 2 && (user.value.clearanceLevel ?? 0) >= 2
  })

  // ★ P3 ABAC 细粒度权限
  /** P3: 知识库编辑权限 — 管理员 或 经理+机密 */
  const canEditKnowledge = computed(() => {
    if (!user.value) return false
    if (user.value.role === 'admin') return true
    const titleLevel = { employee: 1, manager: 2, director: 3 }[user.value.title] || 0
    return titleLevel >= 2 && (user.value.clearanceLevel ?? 0) >= 2
  })

  /** P3: 知识库删除权限 — 仅管理员 或 总监+绝密 */
  const canDeleteKnowledge = computed(() => {
    if (!user.value) return false
    if (user.value.role === 'admin') return true
    return user.value.title === 'director' && (user.value.clearanceLevel ?? 0) >= 3
  })

  /** P3: 数据源配置权限 — 管理员 或 经理+机密 */
  const canConfigureDatasource = computed(() => {
    if (!user.value) return false
    if (user.value.role === 'admin') return true
    const titleLevel = { employee: 1, manager: 2, director: 3 }[user.value.title] || 0
    return titleLevel >= 2 && (user.value.clearanceLevel ?? 0) >= 2
  })

  /** P3: 报表导出权限 — 经理+机密 或 总监 */
  const canExportReport = computed(() => {
    if (!user.value) return false
    const titleLevel = { employee: 1, manager: 2, director: 3 }[user.value.title] || 0
    return titleLevel >= 2 && (user.value.clearanceLevel ?? 0) >= 2
  })

  /** P3: 字段级权限 */
  const fieldLevel = {
    /** 薪资字段可见性 — 职级>=3(总监) 且 密级>=3(绝密) */
    salary: computed(() => {
      if (!user.value) return false
      const titleLevel = { employee: 1, manager: 2, director: 3 }[user.value.title] || 0
      return titleLevel >= 3 && (user.value.clearanceLevel ?? 0) >= 3
    }),
    /** 绩效字段可见性 — 职级>=2(经理) 且 密级>=2(机密) */
    performance: computed(() => {
      if (!user.value) return false
      const titleLevel = { employee: 1, manager: 2, director: 3 }[user.value.title] || 0
      return titleLevel >= 2 && (user.value.clearanceLevel ?? 0) >= 2
    })
  }

  // Actions
  async function login(username: string, password: string) {
    const res = await authApi.login({ username, password })
    const data = res.data
    // ★ 保存双 Token
    accessToken.value = data.accessToken
    refreshToken.value = data.refreshToken
    tokenExpiresAt.value = Date.now() + data.expiresIn * 1000
    setAccessToken(data.accessToken)
    setRefreshToken(data.refreshToken)
    setTokenExpiresAt(Date.now() + data.expiresIn * 1000)

    // ★ 构建含 ABAC 属性的用户数据
    const userData: UserPrincipal = {
      id: data.userId,
      username: data.username,
      realName: '',
      role: data.role,
      email: '',
      phone: '',
      department: data.department || '',
      clearanceLevel: data.clearanceLevel || 0,
      allowedDomains: parseAllowedDomains(data.allowedDomains || ''),
      title: data.title || 'employee',
    }
    user.value = userData
    setUserToStorage(userData as any)
  }

  async function register(data: { username: string; password: string; realName?: string; email?: string; phone?: string }) {
    await authApi.register(data)
  }

  async function fetchUserInfo() {
    const res = await authApi.getUserInfo()
    const u = res.data
    const userData: UserPrincipal = {
      id: u.id,
      username: u.username,
      realName: u.realName,
      role: u.role,
      email: u.email,
      phone: u.phone,
      department: u.department || '',
      clearanceLevel: u.clearanceLevel || 0,
      allowedDomains: parseAllowedDomains(u.allowedDomains || ''),
      title: u.title || 'employee',
    }
    user.value = userData
    setUserToStorage(userData as any)
  }

  // ★ P2 新增：静默刷新 Access Token
  async function refreshAccessToken() {
    if (!refreshToken.value) {
      throw new Error('无可用的 Refresh Token')
    }
    const res = await authApi.refreshToken(refreshToken.value)
    const { accessToken: newAccess, refreshToken: newRefresh, expiresIn } = res.data
    accessToken.value = newAccess
    refreshToken.value = newRefresh
    tokenExpiresAt.value = Date.now() + expiresIn * 1000
    setAccessToken(newAccess)
    setRefreshToken(newRefresh)
    setTokenExpiresAt(Date.now() + expiresIn * 1000)
  }

  // ★ P2 升级：登出时撤销 Refresh Token
  async function logout() {
    try {
      await authApi.revokeToken()
    } catch {
      // 忽略撤销失败
    }
    accessToken.value = null
    refreshToken.value = null
    tokenExpiresAt.value = null
    user.value = null
    removeAllTokens()
    router.push('/login')
  }

  return {
    accessToken,
    refreshToken,
    tokenExpiresAt,
    user,
    isLoggedIn,
    isAdmin,
    login,
    register,
    fetchUserInfo,
    refreshAccessToken,
    logout,
    hasDomain,
    hasClearance,
    isManager,
    isDirector,
    canViewDashboard,
    canManageUsers,
    // P3 ABAC 细粒度权限
    canEditKnowledge,
    canDeleteKnowledge,
    canConfigureDatasource,
    canExportReport,
    fieldLevel,
  }
})
