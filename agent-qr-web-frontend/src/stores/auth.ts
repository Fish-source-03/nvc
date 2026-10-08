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
import type { UserInfo } from '@/types'

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

// ==================== 路由准入判定（批次 11 · R54 补遗） ====================
/**
 * 用户管理页准入判定。
 *
 * <p><b>与后端对齐</b>：`GET /api/admin/users` 使用
 * `@PreAuthorize("hasRole('ADMIN')")`（设计说明书接口表亦标注 ADMIN），
 * 因此前端准入只看 `role`，不参与职级/密级判定。</p>
 *
 * <p><b>修复的错位</b>：原实现（守卫与侧边栏）用「职级>=经理 且 密级>=机密」判定，
 * 造成两头都不对：① role=admin 但 title 为空/偏低的账号被前端误拦，
 * 虽然后端允许（admin 无法进入用户管理）；② 非 admin 的经理看到了入口，
 * 点进去却撞后端 403。</p>
 *
 * @param user 当前用户（只需 role 字段）
 * @returns 是否允许进入用户管理页
 */
export function canAccessUserManage(
  user: Pick<UserPrincipal, 'role'> | null | undefined,
): boolean {
  return user?.role === 'admin'
}

/**
 * 数据仪表盘准入判定：仅总监(director) + 绝密(3)。
 *
 * <p>设计说明书 B3 明确：`canViewDashboard` <b>不豁免 admin</b>
 * （与此并列的还有 canCreateUser），按职级+密级判定——
 * 故此处同样不给 admin 直通，保持前后端口径一致。</p>
 *
 * @param user 当前用户（只需 title/clearanceLevel 字段）
 * @returns 是否允许进入数据仪表盘
 */
export function canAccessDashboard(
  user: Pick<UserPrincipal, 'title' | 'clearanceLevel'> | null | undefined,
): boolean {
  return user?.title === 'director' && user?.clearanceLevel === 3
}

// ==================== 回源数据合并（批次 11 · R54 补遗） ====================
/**
 * 合并 `/api/auth/info` 回源数据与本地已有用户信息。
 *
 * <p><b>防御语义</b>：回源字段<b>缺失</b>（null/undefined）时保留本地已有值；
 * 字段<b>存在</b>时一律以回源值为准——服务端授权变更（收回业务域、调整密级/职级）
 * 需如实反映。</p>
 *
 * <p><b>背景</b>：`/api/auth/info` 曾因后端只回填 id/username/role 而返回残缺用户
 * （department/clearanceLevel/allowedDomains/title 为 null）。若直接按默认值兜底，
 * `fetchUserInfo()` 会把登录时拿到的正确 ABAC 属性覆盖为零值：</p>
 * <ul>
 *   <li>allowedDomains 归零 → 知识库上传的"数据域"选择器为空（R54 已报告）；</li>
 *   <li>title/clearanceLevel 归零 → `canViewDashboard`（需 director+绝密）与
 *       `canManageUsers`（需经理+机密）为 false → 侧边栏丢失「数据仪表盘」「用户管理」
 *       入口，手动访问被路由守卫 403——即使是密级最高的 admin 账号。</li>
 * </ul>
 *
 * @param incoming 回源的用户信息（字段可能因后端版本而缺失）
 * @param previous 本地已有的用户信息（登录时的快照；可为空）
 * @returns 合并后的完整用户主体
 */
export function mergeUserInfo(
  incoming: Partial<UserInfo> | null | undefined,
  previous: UserPrincipal | null | undefined,
): UserPrincipal {
  const prev = previous
  return {
    id: incoming?.id ?? prev?.id ?? 0,
    username: incoming?.username ?? prev?.username ?? '',
    realName: incoming?.realName == null ? (prev?.realName ?? '') : incoming.realName,
    role: incoming?.role == null ? (prev?.role ?? '') : incoming.role,
    email: incoming?.email == null ? (prev?.email ?? '') : incoming.email,
    phone: incoming?.phone == null ? (prev?.phone ?? '') : incoming.phone,
    department: incoming?.department == null ? (prev?.department ?? '') : incoming.department,
    // clearanceLevel 的 0 是合法值（绝密=3 之下的最低档），仅 null/undefined 视为缺失
    clearanceLevel:
      incoming?.clearanceLevel == null ? (prev?.clearanceLevel ?? 0) : incoming.clearanceLevel,
    // 后端返回逗号分隔字符串；字段缺失时保留本地已有的域列表（上传域选择器依赖其非空）
    allowedDomains:
      incoming?.allowedDomains == null
        ? (prev?.allowedDomains ?? [])
        : parseAllowedDomains(incoming.allowedDomains),
    title: incoming?.title == null ? (prev?.title ?? 'employee') : incoming.title,
  }
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

/**
 * 应用初始化时是否应回源拉取用户信息（批次 11 · R19③）。
 *
 * <p>背景：`fetchUserInfo()` 此前**无任何调用点**——刷新页面后 `user` 只来自
 * localStorage 的登录快照，ABAC 授权在服务端变更（如收回业务域、调整密级/职级）
 * 前端一无所知，域选择器仍会展示已失效的域，用户提交后撞上 403。</p>
 *
 * <p>判定规则：<b>只有已登录（存在 access token）才回源</b>。
 * 未登录时 `/api/auth/info` 必然是 403，axios 拦截器会弹出「权限不足」——
 * 在登录页/注册页这种误导性提示正是要避免的。</p>
 *
 * @param hasToken 是否存在 access token（`authStore.isLoggedIn`）
 * @returns true 表示应在初始化时调用 `fetchUserInfo()`
 */
export function shouldHydrateUserInfo(hasToken: boolean): boolean {
  return hasToken
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

  /**
   * ★ 能否创建/管理用户 — 职级>=经理 且 密级>=机密。
   *
   * <p>语义与后端 `AbacEvaluator.canCreateUser` 对齐（经理+机密，**admin 不豁免**，
   * 设计文档 B3）。用于用户管理页内「+ 创建用户」按钮的显示控制（v-permission）。
   * <b>不是</b>页面准入判定——页面准入见 {@link canAccessUserManage}（仅 admin，
   * 对齐后端 `GET /api/admin/users` 的 hasRole('ADMIN')）。</p>
   */
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
    // 回源数据与本地快照合并：缺失字段保留本地值（防御旧后端残缺响应），
    // 详见 mergeUserInfo 的 javadoc。
    const userData = mergeUserInfo(res.data, user.value)
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
