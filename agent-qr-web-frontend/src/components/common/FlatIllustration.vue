<script setup lang="ts">
/**
 * FlatIllustration — 扁平化 2D 矢量插画组件
 *
 * 提供与登录页角色一致的扁平几何风格 SVG 插画。
 * 所有插画使用品牌角色色系 (Orange/Purple/Yellow/Black)。
 */

defineProps<{
  /** 插画名称 */
  name: 'empty-chat' | 'empty-documents' | 'empty-data' | 'welcome' | 'error-403' | 'error-404'
}>()
</script>

<template>
  <div class="flat-illustration">
    <!-- ====== empty-chat: 角色 + 对话框 ====== -->
    <svg v-if="name === 'empty-chat'" viewBox="0 0 200 160" xmlns="http://www.w3.org/2000/svg">
      <!-- 角色身体 (Purple) -->
      <rect x="35" y="85" width="40" height="55" rx="8" fill="#6C3FF5" />
      <!-- 头部 -->
      <circle cx="55" cy="72" r="22" fill="#6C3FF5" />
      <!-- 眼睛 -->
      <circle cx="48" cy="68" r="4" fill="#2D2D2D" />
      <circle cx="62" cy="68" r="4" fill="#2D2D2D" />
      <!-- 对话气泡 -->
      <rect x="70" y="30" width="95" height="45" rx="12" fill="#F5F6FA" stroke="#E8E8ED" stroke-width="2" />
      <circle cx="78" cy="52" r="4" fill="#E8E8ED" />
      <circle cx="90" cy="52" r="4" fill="#E8E8ED" />
      <circle cx="102" cy="52" r="4" fill="#E8E8ED" />
      <!-- 气泡尾巴 -->
      <polygon points="72,68 60,78 76,72" fill="#F5F6FA" stroke="#E8E8ED" stroke-width="2" />
    </svg>

    <!-- ====== empty-documents: 角色 + 文档 ====== -->
    <svg v-else-if="name === 'empty-documents'" viewBox="0 0 200 160" xmlns="http://www.w3.org/2000/svg">
      <!-- 角色身体 (Orange) -->
      <path d="M40 140 Q40 90 75 90 Q110 90 110 140" fill="#FF9B6B" />
      <!-- 眼睛 -->
      <circle cx="62" cy="108" r="4" fill="#2D2D2D" />
      <circle cx="82" cy="108" r="4" fill="#2D2D2D" />
      <!-- 嘴巴 (扇形笑) -->
      <path d="M64 118 Q72 126 80 118" fill="#2D2D2D" />
      <!-- 文档 1 -->
      <rect x="105" y="55" width="50" height="65" rx="4" fill="#fff" stroke="#E8E8ED" stroke-width="2" />
      <rect x="115" y="68" width="30" height="4" rx="2" fill="#E8E8ED" />
      <rect x="115" y="78" width="25" height="4" rx="2" fill="#E8E8ED" />
      <rect x="115" y="88" width="20" height="4" rx="2" fill="#E8E8ED" />
      <!-- 文档 2 (behind) -->
      <rect x="120" y="48" width="50" height="65" rx="4" fill="#fff" stroke="#F0F0F5" stroke-width="2" />
      <rect x="130" y="58" width="30" height="4" rx="2" fill="#F0F0F5" />
      <rect x="130" y="68" width="20" height="4" rx="2" fill="#F0F0F5" />
    </svg>

    <!-- ====== empty-data: 角色 + 数据库圆柱 ====== -->
    <svg v-else-if="name === 'empty-data'" viewBox="0 0 200 160" xmlns="http://www.w3.org/2000/svg">
      <!-- 角色身体 (Yellow) -->
      <path d="M40 135 Q40 95 72 95 Q104 95 104 135" fill="#E8D754" />
      <!-- 眼睛 -->
      <circle cx="58" cy="108" r="4" fill="#2D2D2D" />
      <circle cx="80" cy="108" r="4" fill="#2D2D2D" />
      <!-- 嘴巴 -->
      <rect x="65" y="118" width="10" height="3" rx="1.5" fill="#2D2D2D" />
      <!-- 数据库 1 -->
      <ellipse cx="145" cy="60" rx="28" ry="10" fill="#6C3FF5" opacity="0.7" />
      <rect x="117" y="60" width="56" height="30" fill="#6C3FF5" />
      <ellipse cx="145" cy="90" rx="28" ry="10" fill="#6C3FF5" />
      <ellipse cx="145" cy="90" rx="28" ry="10" fill="none" stroke="#6C3FF5" stroke-width="3" opacity="0.5" />
      <!-- 数据库 2 (smaller, behind) -->
      <ellipse cx="160" cy="52" rx="22" ry="8" fill="#FF9B6B" opacity="0.5" />
      <rect x="138" y="52" width="44" height="22" fill="#FF9B6B" opacity="0.5" />
      <ellipse cx="160" cy="74" rx="22" ry="8" fill="#FF9B6B" opacity="0.5" />
    </svg>

    <!-- ====== welcome: 四个迷你角色 ====== -->
    <svg v-else-if="name === 'welcome'" viewBox="0 0 200 120" xmlns="http://www.w3.org/2000/svg">
      <!-- Orange mini -->
      <path d="M25 90 Q25 55 45 55 Q65 55 65 90" fill="#FF9B6B" />
      <circle cx="40" cy="68" r="2.5" fill="#2D2D2D" />
      <circle cx="50" cy="68" r="2.5" fill="#2D2D2D" />
      <path d="M42 74 Q45 78 48 74" fill="#2D2D2D" />
      <!-- Purple mini (tall) -->
      <rect x="70" y="45" width="24" height="45" rx="6" fill="#6C3FF5" />
      <circle cx="82" cy="35" r="14" fill="#6C3FF5" />
      <circle cx="77" cy="32" r="2.5" fill="#2D2D2D" />
      <circle cx="87" cy="32" r="2.5" fill="#2D2D2D" />
      <rect x="79" y="40" width="6" height="2" rx="1" fill="#2D2D2D" />
      <!-- Black mini -->
      <rect x="105" y="55" width="22" height="35" rx="5" fill="#2D2D2D" />
      <circle cx="116" cy="46" r="12" fill="#2D2D2D" />
      <circle cx="112" cy="44" r="2" fill="#fff" />
      <circle cx="120" cy="44" r="2" fill="#fff" />
      <!-- Yellow mini -->
      <path d="M140 90 Q140 60 157 60 Q174 60 174 90" fill="#E8D754" />
      <circle cx="152" cy="72" r="2.5" fill="#2D2D2D" />
      <circle cx="162" cy="72" r="2.5" fill="#2D2D2D" />
      <rect x="154" y="79" width="6" height="2" rx="1" fill="#2D2D2D" />
    </svg>

    <!-- ====== error-403: 角色双手交叉 (禁止) ====== -->
    <svg v-else-if="name === 'error-403'" viewBox="0 0 200 160" xmlns="http://www.w3.org/2000/svg">
      <!-- 角色身体 (Black) -->
      <rect x="68" y="70" width="50" height="65" rx="10" fill="#2D2D2D" />
      <!-- 头部 -->
      <circle cx="93" cy="52" r="28" fill="#2D2D2D" />
      <!-- 眼睛 (X 形表示生气) -->
      <line x1="80" y1="44" x2="92" y2="52" stroke="#fff" stroke-width="2.5" stroke-linecap="round" />
      <line x1="92" y1="44" x2="80" y2="52" stroke="#fff" stroke-width="2.5" stroke-linecap="round" />
      <line x1="94" y1="44" x2="106" y2="52" stroke="#fff" stroke-width="2.5" stroke-linecap="round" />
      <line x1="106" y1="44" x2="94" y2="52" stroke="#fff" stroke-width="2.5" stroke-linecap="round" />
      <!-- 嘴巴 -->
      <rect x="86" y="60" width="14" height="4" rx="2" fill="#fff" opacity="0.6" />
      <!-- 手臂交叉 X -->
      <rect x="55" y="88" width="76" height="8" rx="4" fill="#FF4D4F" transform="rotate(-15 93 92)" />
      <rect x="55" y="88" width="76" height="8" rx="4" fill="#FF4D4F" transform="rotate(15 93 92)" />
      <!-- 禁止标志 -->
      <circle cx="150" cy="55" r="22" fill="#FFEDED" stroke="#FF4D4F" stroke-width="3" />
      <rect x="137" y="53" width="26" height="5" rx="2.5" fill="#FF4D4F" />
    </svg>

    <!-- ====== error-404: 角色 + 放大镜 (困惑) ====== -->
    <svg v-else-if="name === 'error-404'" viewBox="0 0 200 160" xmlns="http://www.w3.org/2000/svg">
      <!-- 角色身体 (Purple) -->
      <rect x="40" y="75" width="40" height="60" rx="8" fill="#6C3FF5" />
      <!-- 头部 -->
      <circle cx="60" cy="58" r="24" fill="#6C3FF5" />
      <!-- 眼睛 (大大的, 困惑状 — 一大一小) -->
      <circle cx="50" cy="54" r="5" fill="#fff" />
      <circle cx="50" cy="54" r="3" fill="#2D2D2D" />
      <circle cx="70" cy="54" r="3.5" fill="#fff" />
      <circle cx="70" cy="54" r="2" fill="#2D2D2D" />
      <!-- 嘴巴 (小圆形, 惊讶) -->
      <circle cx="60" cy="66" r="3" fill="#2D2D2D" />
      <!-- 问号 -->
      <text x="85" y="45" font-size="20" font-weight="700" fill="#6C3FF5" font-family="sans-serif">?</text>
      <!-- 放大镜 -->
      <circle cx="145" cy="75" r="22" fill="none" stroke="#4F7CFF" stroke-width="3.5" />
      <line x1="160" y1="90" x2="175" y2="105" stroke="#4F7CFF" stroke-width="4" stroke-linecap="round" />
      <!-- 放大镜手柄末端 -->
      <circle cx="178" cy="108" r="3" fill="#4F7CFF" />
    </svg>
  </div>
</template>

<style scoped lang="scss">
.flat-illustration {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  max-width: 280px;
  margin: 0 auto;

  svg {
    width: 100%;
    height: auto;
  }
}
</style>
