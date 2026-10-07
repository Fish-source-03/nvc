<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore, shouldHydrateUserInfo } from '@/stores/auth'
import GuestLayout from '@/components/layout/GuestLayout.vue'
import MainLayout from '@/components/layout/MainLayout.vue'

const route = useRoute()
const authStore = useAuthStore()

const layout = computed(() => {
  return route.meta.layout === 'main' ? 'main' : 'guest'
})

/**
 * ★ 批次 11 · R19③：应用初始化时回源用户信息。
 *
 * 此前 `fetchUserInfo()` 无任何调用点：刷新页面后 `user` 只来自 localStorage，
 * 服务端侧的 ABAC 变更（收回业务域、调整密级/职级）不会反映到前端，
 * 域选择器会继续展示已失效的域，用户提交后撞 403。
 *
 * 未登录时不调用（否则 `/api/auth/info` 的 403 会在登录页弹出误导性的「权限不足」）；
 * 失败时静默——401 的重刷新与跳转已由 axios 拦截器统一处理，这里不重复提示。
 */
onMounted(async () => {
  if (!shouldHydrateUserInfo(authStore.isLoggedIn)) return
  try {
    await authStore.fetchUserInfo()
  } catch {
    // 静默降级：沿用 localStorage 中的快照
  }
})
</script>

<template>
  <component :is="layout === 'main' ? MainLayout : GuestLayout" />
</template>
