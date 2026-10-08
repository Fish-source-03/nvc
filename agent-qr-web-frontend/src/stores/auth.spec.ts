// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { DOMAINS } from '@/types'
import type { UserInfo } from '@/types'
import {
  normalizeDomains,
  resolveAvailableDomains,
  pickDefaultDomain,
  shouldHydrateUserInfo,
  mergeUserInfo,
  canAccessUserManage,
  canAccessDashboard,
} from './auth'
import type { UserPrincipal } from './auth'
// 以原始文本导入根组件（vite ?raw）——jsdom 不可用，组件层只能做源码级护栏
import appSource from '../App.vue?raw'
import authSource from './auth.ts?raw'
import routerSource from '../router/index.ts?raw'
import sidebarSource from '../components/layout/Sidebar.vue?raw'

/**
 * 批次 03 前端联动修复 —— 域选择默认值。
 *
 * <p>背景：后端 `/api/chat/ask` 与 `/api/chat/ask/stream` 已强制校验 domain
 * （缺失 → 业务码 400、越域 → 403）。原前端域选择器默认「全部域」（空值），
 * 导致问答必然失败，且 SSE 只看 HTTP 状态，界面表现为"无响应"。
 * 修复后默认选中用户首个可用域，且不允许空域发出请求。</p>
 *
 * <p>环境说明：本仓库 jsdom 环境不可用（`html-encoding-sniffer` 触发
 * `ERR_REQUIRE_ESM`），故组件层无法自动化渲染测试；选域逻辑已提取为
 * 纯函数，本文件以 node 环境覆盖之。</p>
 */
describe('chat domain selection (batch-03)', () => {
  describe('normalizeDomains', () => {
    it('空值 / 未定义 / 空数组均返回空数组', () => {
      expect(normalizeDomains(undefined)).toEqual([])
      expect(normalizeDomains(null)).toEqual([])
      expect(normalizeDomains([])).toEqual([])
    })

    it('剔除空白项与重复项，并保持原有顺序', () => {
      expect(normalizeDomains(['HR', '  ', 'FINANCE', 'HR', '', 'RD'])).toEqual([
        'HR',
        'FINANCE',
        'RD',
      ])
    })

    it('裁剪每项两侧空白', () => {
      expect(normalizeDomains([' HR ', 'FINANCE '])).toEqual(['HR', 'FINANCE'])
    })
  })

  describe('pickDefaultDomain', () => {
    it('普通用户取首个可用域', () => {
      expect(pickDefaultDomain(['FINANCE', 'COMMON'], false)).toBe('FINANCE')
    })

    it('跳过列表中的空串 / 空白项，取首个真实域', () => {
      expect(pickDefaultDomain(['', '   ', 'RD', 'RD'], false)).toBe('RD')
    })

    it('普通用户无任何可用域时返回 null（调用方须禁用发送并提示）', () => {
      expect(pickDefaultDomain([], false)).toBeNull()
      expect(pickDefaultDomain(undefined, false)).toBeNull()
      expect(pickDefaultDomain(null, false)).toBeNull()
      expect(pickDefaultDomain(['', '  '], false)).toBeNull()
    })

    it('admin 的 allowedDomains 为空时回退到内置全量域首项（后端 ABAC 对 admin 直通）', () => {
      expect(pickDefaultDomain([], true)).toBe(DOMAINS[0])
      expect(pickDefaultDomain(undefined, true)).toBe(DOMAINS[0])
    })

    it('admin 有显式授权域时仍以其列表为准', () => {
      expect(pickDefaultDomain(['SALES', 'HR'], true)).toBe('SALES')
    })

    it('返回值要么为 null，要么非空字符串 —— 绝不会是空串', () => {
      const picked = [
        pickDefaultDomain([], false),
        pickDefaultDomain(undefined, false),
        pickDefaultDomain(['  '], false),
        pickDefaultDomain(['HR'], false),
        pickDefaultDomain(null, true),
        pickDefaultDomain(['', 'COMMON'], true),
      ]
      for (const domain of picked) {
        expect(domain === null || domain.trim().length > 0).toBe(true)
      }
    })
  })

  describe('resolveAvailableDomains', () => {
    it('有授权域时原样（去重后）返回，普通用户与 admin 一致', () => {
      expect(resolveAvailableDomains(['HR', 'COMMON'], false)).toEqual(['HR', 'COMMON'])
      expect(resolveAvailableDomains(['HR', 'COMMON'], true)).toEqual(['HR', 'COMMON'])
    })

    it('admin 无授权域时得到内置全量域', () => {
      expect(resolveAvailableDomains([], true)).toEqual([...DOMAINS])
    })

    it('普通用户无授权域时得到空列表（→ 前端禁用发送）', () => {
      expect(resolveAvailableDomains([], false)).toEqual([])
      expect(resolveAvailableDomains(null, false)).toEqual([])
    })

    it('返回新数组，调用方修改不会污染入参', () => {
      const input = ['HR']
      const output = resolveAvailableDomains(input, false)
      output.push('FINANCE')
      expect(input).toEqual(['HR'])
    })

    it('admin 回退分支返回的也是副本，不会污染内置常量', () => {
      const output = resolveAvailableDomains([], true)
      output.pop()
      expect(resolveAvailableDomains([], true)).toEqual([...DOMAINS])
    })
  })
})

/**
 * 批次 11 · R19③：`fetchUserInfo()` 的接线。
 *
 * 缺陷：该方法此前无任何调用点，刷新页面后 `user` 仅来自 localStorage 快照，
 * 服务端 ABAC 变更（收回业务域等）不反映到前端，域选择器会展示已失效的域。
 */
describe('shouldHydrateUserInfo · 初始化回源判定（batch-11 / R19③）', () => {
  it('★ 未登录（无 token）→ 不回源（否则登录页会弹出误导性的「权限不足」）', () => {
    expect(shouldHydrateUserInfo(false)).toBe(false)
  })

  it('★ 已登录 → 回源（服务端授权可能已变更，本地快照不作数）', () => {
    expect(shouldHydrateUserInfo(true)).toBe(true)
  })
})

describe('App.vue · 初始化回源接线护栏（batch-11 / R19③）', () => {
  it('★ 根组件在挂载时调用 fetchUserInfo，并先过 shouldHydrateUserInfo 守卫', () => {
    expect(appSource).toContain('shouldHydrateUserInfo')
    expect(appSource).toContain('authStore.fetchUserInfo()')
    // 守卫必须在调用之前（未登录时不得发出必然 403 的请求）
    expect(appSource.indexOf('shouldHydrateUserInfo(authStore.isLoggedIn)')).toBeLessThan(
      appSource.indexOf('authStore.fetchUserInfo()'),
    )
  })

  it('★ 回源失败不阻断启动（静默降级，401 交给 axios 拦截器）', () => {
    expect(appSource).toContain('catch')
  })
})

/**
 * 批次 11 · R54 补遗：`fetchUserInfo()` 的回源合并防御。
 *
 * <p>缺陷：`/api/auth/info` 返回<b>残缺用户</b>（旧后端只回填 id/username/role）时，
 * `fetchUserInfo()` 此前把登录时正确的 ABAC 属性覆盖为零值——
 * allowedDomains → []（上传「数据域」选择器为空，R54）、title → 'employee'、
 * clearanceLevel → 0（「数据仪表盘」「用户管理」入口消失、路由守卫 403，
 * 即使密级最高的 admin 账号也无法查看）。</p>
 */
describe('mergeUserInfo · 回源数据合并（batch-11 / R54 补遗）', () => {
  const previous: UserPrincipal = {
    id: 4,
    username: 'admin',
    realName: '系统管理员',
    role: 'admin',
    email: 'admin@corp.com',
    phone: '13800000000',
    department: 'COMMON',
    clearanceLevel: 3,
    allowedDomains: ['HR', 'FINANCE'],
    title: 'director',
  }

  it('★ 残缺响应（仅 id/username/role）不覆盖本地 ABAC 属性 —— 线上问题的直接场景', () => {
    const merged = mergeUserInfo({ id: 4, username: 'admin', role: 'admin' }, previous)

    expect(merged.title).toBe('director')            // 数据仪表盘/用户管理入口依赖
    expect(merged.clearanceLevel).toBe(3)            // 同上
    expect(merged.allowedDomains).toEqual(['HR', 'FINANCE']) // 上传域选择器依赖
    expect(merged.department).toBe('COMMON')
    expect(merged.realName).toBe('系统管理员')
  })

  it('★ 完整响应以回源值为准（服务端授权变更如实反映）', () => {
    const merged = mergeUserInfo(
      {
        id: 4,
        username: 'admin',
        realName: '改名',
        role: 'user',
        email: 'x@y.z',
        phone: '139',
        department: 'RD',
        clearanceLevel: 1,
        allowedDomains: 'RD',
        title: 'employee',
      } as UserInfo,
      previous,
    )

    expect(merged.role).toBe('user')
    expect(merged.title).toBe('employee')
    expect(merged.clearanceLevel).toBe(1)
    expect(merged.allowedDomains).toEqual(['RD'])
    expect(merged.department).toBe('RD')
  })

  it('allowedDomains 字符串正常解析为数组（逗号分隔 + 逐项裁剪）', () => {
    const merged = mergeUserInfo({ allowedDomains: 'HR, FINANCE ,RD' } as Partial<UserInfo>, previous)

    expect(merged.allowedDomains).toEqual(['HR', 'FINANCE', 'RD'])
  })

  it('clearanceLevel=0 是合法值而非缺失（不得被旧值覆盖）', () => {
    const merged = mergeUserInfo({ clearanceLevel: 0 } as Partial<UserInfo>, previous)

    expect(merged.clearanceLevel).toBe(0)
  })

  it('无本地快照（previous=null）时缺失字段得到安全默认值', () => {
    const merged = mergeUserInfo({ id: 9, username: 'newbie', role: 'user' }, null)

    expect(merged.title).toBe('employee')
    expect(merged.clearanceLevel).toBe(0)
    expect(merged.allowedDomains).toEqual([])
    expect(merged.department).toBe('')
  })

  it('部分缺失逐字段独立处理（缺失保旧、存在取新）', () => {
    const merged = mergeUserInfo(
      { id: 4, username: 'admin', role: 'admin', department: 'SALES' } as Partial<UserInfo>,
      previous,
    )

    expect(merged.department).toBe('SALES')  // 存在 → 取回源值
    expect(merged.title).toBe('director')    // 缺失 → 保留旧值
    expect(merged.clearanceLevel).toBe(3)    // 缺失 → 保留旧值
  })

  it('incoming 为 null/undefined 时整体保留本地快照', () => {
    expect(mergeUserInfo(null, previous)).toEqual(previous)
    expect(mergeUserInfo(undefined, previous)).toEqual(previous)
  })
})

describe('fetchUserInfo · 合并接线护栏（batch-11 / R54 补遗）', () => {
  it('★ fetchUserInfo 通过 mergeUserInfo 合并回源数据（不得再逐字段裸解析）', () => {
    expect(authSource).toContain('mergeUserInfo(res.data, user.value)')
    // 旧的零值兜底写法不得复活（会覆盖登录时的正确 ABAC 属性）
    expect(authSource).not.toContain("u.title || 'employee'")
    expect(authSource).not.toContain('u.clearanceLevel || 0')
  })
})

/**
 * 批次 11 · R54 补遗：路由/菜单准入判定。
 *
 * <p>缺陷（用户报告）：即使 role=admin、密级最高，仍无法查看「数据仪表盘」「用户管理」。
 * 两个成因：① `fetchUserInfo` 覆盖零值（mergeUserInfo 已修）；
 * ② 用户管理页前端判定与后端错位——前端要「职级>=经理 且 密级>=机密」，
 * 后端只要 `hasRole('ADMIN')`，导致 title 偏低的 admin 被前端误拦、
 * 非 admin 的经理看到入口点进去却 403。</p>
 */
describe('路由准入判定（batch-11 / R54 补遗）', () => {
  it('★ 用户管理页：仅 admin（对齐后端 hasRole(ADMIN)，密级/职级不参与判定）', () => {
    expect(canAccessUserManage({ role: 'admin' })).toBe(true)
    expect(canAccessUserManage({ role: 'user' })).toBe(false)
    expect(canAccessUserManage({ role: 'manager' })).toBe(false)
    expect(canAccessUserManage(null)).toBe(false)
    expect(canAccessUserManage(undefined)).toBe(false)
  })

  it('★ 数据仪表盘：仅总监+绝密；admin 不豁免（设计 B3，前后端同口径）', () => {
    expect(canAccessDashboard({ title: 'director', clearanceLevel: 3 })).toBe(true)
    expect(canAccessDashboard({ title: 'director', clearanceLevel: 2 })).toBe(false)
    expect(canAccessDashboard({ title: 'manager', clearanceLevel: 3 })).toBe(false)
    expect(canAccessDashboard({ title: '', clearanceLevel: 0 })).toBe(false)
    expect(canAccessDashboard(null)).toBe(false)
  })
})

describe('路由/菜单接线护栏（batch-11 / R54 补遗）', () => {
  it('★ 守卫使用 canAccessUserManage / canAccessDashboard（不得回退为内联职级判定）', () => {
    expect(routerSource).toContain('!canAccessUserManage(user)')
    expect(routerSource).toContain('!canAccessDashboard(user)')
    // 旧的内联职级判定不得复活
    expect(routerSource).not.toContain('titleLevel < 2')
  })

  it('★ 侧边栏「用户管理」入口以 isAdmin 显示（与守卫/后端同口径）', () => {
    expect(sidebarSource).toContain('authStore.isAdmin')
    // 不得再用职级+密级判定作为用户管理入口的显示条件
    expect(sidebarSource).not.toContain('authStore.canManageUsers')
  })
})
