<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const authStore = useAuthStore()

const defaultActive = computed(() => {
  const path = route.path
  if (path.startsWith('/admin/knowledge')) return '/admin/knowledge'
  if (path.startsWith('/admin/users')) return '/admin/users'
  if (path.startsWith('/admin/dashboard')) return '/admin/dashboard'
  if (path.startsWith('/admin/datasource')) return '/admin/datasource'
  if (path.startsWith('/admin/catalog')) return '/admin/catalog'
  if (path.startsWith('/admin/quality')) return '/admin/quality'
  return '/chat'
})

const menuItems = computed(() => {
  const items = [
    { path: '/chat', title: t('sidebar.chat'), icon: 'ChatDotRound' }
  ]

  if (authStore.isAdmin) {
    items.push(
      { path: '/admin/knowledge', title: t('sidebar.knowledge'), icon: 'Document' },
      { path: '/admin/datasource', title: t('sidebar.datasource'), icon: 'Connection' },
      { path: '/admin/quality', title: t('sidebar.quality'), icon: 'Warning' }
    )
  }

  // 用户管理入口与路由守卫/后端（hasRole('ADMIN')）同口径：仅 admin 显示。
  // 原判定用 canManageUsers（职级+密级）会与后端错位：title 偏低的 admin 看不到入口、
  // 非 admin 的经理看到入口却 403。
  if (authStore.isAdmin) {
    items.push(
      { path: '/admin/users', title: t('sidebar.users'), icon: 'User' }
    )
  }

  if (authStore.canViewDashboard) {
    items.push(
      { path: '/admin/dashboard', title: t('sidebar.dashboard'), icon: 'DataAnalysis' }
    )
  }

  if (authStore.isAdmin || authStore.isManager() || authStore.isDirector()) {
    items.push(
      { path: '/admin/catalog', title: t('sidebar.catalog'), icon: 'FolderOpened' }
    )
  }

  return items
})

function handleSelect(path: string) {
  router.push(path)
  // 移动端点击菜单项后关闭抽屉
  if (appStore.mobileDrawerOpen) {
    appStore.closeMobileDrawer()
  }
}
</script>

<template>
  <!-- 移动端 overlay -->
  <div
    v-if="appStore.mobileDrawerOpen"
    class="sidebar-overlay"
    @click="appStore.closeMobileDrawer()"
  />

  <div
    class="sidebar"
    :class="{
      'sidebar--mobile-open': appStore.mobileDrawerOpen,
      'sidebar--collapsed': appStore.sidebarCollapsed
    }"
  >
    <el-menu
      :default-active="defaultActive"
      :collapse="appStore.sidebarCollapsed && !appStore.isMobile"
      :router="false"
      background-color="#1E293B"
      text-color="#A0AEC0"
      active-text-color="#6C3FF5"
      @select="handleSelect"
    >
      <template v-for="item in menuItems" :key="item.path">
        <el-menu-item :index="item.path">
          <el-icon>
            <component :is="item.icon" />
          </el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </template>
    </el-menu>
  </div>
</template>

<style scoped lang="scss">
.sidebar {
  height: 100%;
  background-color: $sidebar-bg;
  overflow-y: auto;
  transition: transform var(--transition-base);

  .el-menu {
    border-right: none;
    height: 100%;
  }

  // 激活项左侧紫色竖条指示器
  :deep(.el-menu-item.is-active) {
    position: relative;

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 50%;
      transform: translateY(-50%);
      width: 3px;
      height: 24px;
      border-radius: 0 2px 2px 0;
      background: $login-purple;
    }
  }

  // 移动端：默认隐藏在左侧外
  @media (max-width: 767px) {
    position: fixed;
    left: 0;
    top: 0;
    z-index: var(--z-drawer);
    width: 220px;
    transform: translateX(-100%);

    &--mobile-open {
      transform: translateX(0);
      box-shadow: 2px 0 8px rgba(0, 0, 0, 0.15);
    }
  }
}

// 移动端 overlay
.sidebar-overlay {
  display: none;

  @media (max-width: 767px) {
    display: block;
    position: fixed;
    inset: 0;
    z-index: var(--z-overlay);
    background-color: rgba(0, 0, 0, 0.3);
  }
}
</style>
