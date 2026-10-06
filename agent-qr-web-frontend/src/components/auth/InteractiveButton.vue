<script setup lang="ts">
import { ArrowRight } from '@element-plus/icons-vue'

withDefaults(defineProps<{
  loading?: boolean
  disabled?: boolean
}>(), {
  loading: false,
  disabled: false,
})
</script>

<template>
  <button
    class="interactive-btn"
    :class="{ 'interactive-btn--loading': loading }"
    :disabled="disabled || loading"
    type="submit"
  >
    <span class="interactive-btn__content">
      <span class="interactive-btn__text">
        <slot />
      </span>
      <span class="interactive-btn__icon">
        <el-icon><ArrowRight /></el-icon>
      </span>
    </span>
    <span class="interactive-btn__overlay" />
  </button>
</template>

<style scoped lang="scss">
.interactive-btn {
  position: relative;
  width: 100%;
  height: 48px;
  border: 1px solid $border-color;
  border-radius: var(--radius-2xl);
  background: transparent;
  cursor: pointer;
  overflow: hidden;
  font-size: $font-size-large;
  font-weight: var(--font-weight-medium);
  color: $text-primary;
  transition: border-color var(--transition-base), box-shadow var(--transition-base);

  &:hover:not(:disabled) {
    border-color: $primary-color;
    box-shadow: 0 2px 16px rgba($primary-color, 0.15);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.7;
  }

  // 内容层
  &__content {
    position: relative;
    z-index: 2;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: var(--space-2);
    height: 100%;
    transition: transform var(--transition-base);
  }

  // 文字
  &__text {
    transition: transform var(--transition-base), opacity var(--transition-base);
  }

  // 箭头图标
  &__icon {
    position: absolute;
    right: -32px;
    opacity: 0;
    display: flex;
    align-items: center;
    transition: all var(--transition-base);
    font-size: var(--font-size-lg);
    color: #fff;
  }

  // 背景覆盖层
  &__overlay {
    position: absolute;
    inset: 0;
    z-index: 1;
    background: $primary-color;
    transform: translateX(-100%);
    transition: transform var(--transition-base);
    border-radius: var(--radius-2xl);
  }

  // 悬停效果
  &:hover:not(:disabled) {
    .interactive-btn__content {
      transform: translateX(12px);
    }
    .interactive-btn__text {
      transform: translateX(20px);
      opacity: 0;
    }
    .interactive-btn__icon {
      right: 50%;
      transform: translateX(50%);
      opacity: 1;
    }
    .interactive-btn__overlay {
      transform: translateX(0);
    }
    color: #fff;
  }

  // Loading 状态保持主色背景
  &--loading {
    background: $primary-color;
    border-color: $primary-color;
    color: #fff;

    .interactive-btn__icon {
      right: 50%;
      transform: translateX(50%);
      opacity: 1;
    }
    .interactive-btn__text {
      opacity: 0.7;
    }
  }
}
</style>
