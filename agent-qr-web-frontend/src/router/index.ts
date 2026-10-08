import { createRouter, createWebHistory } from 'vue-router'
import { getAccessToken, getUserFromStorage, getUserRoleFromLocalStorage } from '@/utils/token'
import { useAuthStore, canAccessDashboard, canAccessUserManage } from '@/stores/auth'

// ★ P2 扩展路由 Meta 类型
declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    requiresAdmin?: boolean
    requiresDomain?: string   // ★ P2 ABAC 域检查
    requiresTitle?: string[]  // ★ P2 ABAC 职级检查
    layout?: 'main' | 'guest'
    title?: string
    guest?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/chat',
    },
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/login/LoginView.vue'),
      meta: { guest: true, layout: 'guest' },
    },
    {
      path: '/register',
      name: 'Register',
      component: () => import('@/views/register/RegisterView.vue'),
      meta: { guest: true, layout: 'guest' },
    },
    {
      path: '/chat',
      name: 'Chat',
      component: () => import('@/views/chat/ChatView.vue'),
      meta: { requiresAuth: true, layout: 'main', title: '问答' },
    },
    {
      path: '/admin/users',
      name: 'UserManage',
      component: () => import('@/views/user/UserManageView.vue'),
      meta: { requiresAuth: true, layout: 'main', title: '用户管理' },
    },
    {
      path: '/admin/knowledge',
      name: 'Knowledge',
      component: () => import('@/views/knowledge/KnowledgeView.vue'),
      meta: { requiresAuth: true, requiresAdmin: true, layout: 'main', title: '知识库管理' },
    },
    {
      path: '/admin/dashboard',
      name: 'Dashboard',
      component: () => import('@/views/dashboard/DashboardView.vue'),
      meta: { requiresAuth: true, layout: 'main', title: '数据仪表盘' },
    },
    // ★ P2 新增路由
    {
      path: '/admin/datasource',
      name: 'DataSource',
      component: () => import('@/views/datasource/DataSourceView.vue'),
      meta: { requiresAuth: true, requiresAdmin: true, layout: 'main', title: '数据接入' },
    },
    {
      path: '/admin/catalog',
      name: 'Catalog',
      component: () => import('@/views/catalog/CatalogView.vue'),
      meta: { requiresAuth: true, requiresAdmin: true, layout: 'main', title: '知识目录' },
    },
    {
      path: '/admin/quality',
      name: 'QualityReport',
      component: () => import('@/views/dataquality/QualityReportView.vue'),
      meta: { requiresAuth: true, requiresAdmin: true, layout: 'main', title: '质量报告' },
    },
    // ★ P3 新增路由
    {
      path: '/admin/quality/rules',
      name: 'QualityRules',
      component: () => import('@/views/quality/RulesManager.vue'),
      meta: { requiresAuth: true, requiresAdmin: true, layout: 'main', title: '规则管理' },
    },
    {
      path: '/403',
      name: 'Forbidden',
      component: () => import('@/views/error/403.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'NotFound',
      component: () => import('@/views/error/404.vue'),
    },
  ],
})

// ★ P2 ABAC 路由守卫升级
router.beforeEach((to, _from, next) => {
  let token: string | null = null
  let user: any = null

  try {
    const authStore = useAuthStore()
    token = authStore.accessToken
    user = authStore.user
  } catch {
    token = getAccessToken()
    user = getUserFromStorage()
  }

  if (to.path === '/login' || to.path === '/register') {
    // 已登录用户访问登录/注册页 → 重定向到 /chat
    token ? next('/chat') : next()
  } else {
    if (!token) {
      // 未登录 → 重定向到 /login，携带 redirect 参数
      next(`/login?redirect=${to.path}`)
      return
    }

    // ★ P1 角色判断保留（admin 专有页面）
    if (to.meta.requiresAdmin && user?.role !== 'admin') {
      next('/403')
      return
    }

    // ★ 数据仪表盘 → 仅总监+绝密（设计 B3：admin 不豁免；与后端 canViewDashboard 同口径）
    if (to.path === '/admin/dashboard' && !canAccessDashboard(user)) {
      next('/403')
      return
    }

    // ★ 用户管理 → 仅 admin（对齐后端 GET /api/admin/users 的 hasRole('ADMIN')；
    //   原「职级>=经理 且 密级>=机密」判定会把 title 偏低的 admin 误拦在外）
    if (to.path === '/admin/users' && !canAccessUserManage(user)) {
      next('/403')
      return
    }

    // ★ P2 ABAC 扩展：页面级域权限
    if (to.meta.requiresDomain && user) {
      const requiredDomain = to.meta.requiresDomain as string
      const allowedDomains: string[] = user.allowedDomains || []
      if (!allowedDomains.includes(requiredDomain)) {
        next('/403')
        return
      }
    }

    // ★ P2 ABAC 扩展：职级限制
    if (to.meta.requiresTitle && user) {
      const allowedTitles = to.meta.requiresTitle as string[]
      if (!allowedTitles.includes(user.title || '')) {
        next('/403')
        return
      }
    }

    next()
  }
})

export default router
