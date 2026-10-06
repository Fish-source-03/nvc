<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted, watch, computed } from 'vue'

const props = withDefaults(defineProps<{
  isTyping?: boolean
  showPassword?: boolean
  passwordLength?: number
  isError?: boolean
}>(), {
  isTyping: false,
  showPassword: false,
  passwordLength: 0,
  isError: false,
})

const emit = defineEmits<{
  (e: 'errorDone'): void
}>()

// ==================== 工具函数 ====================
const clamp = (min: number, max: number, v: number) => Math.max(min, Math.min(max, v))

// ==================== 入场动画 ====================
const entranceDone = ref(false)
const charAnimated = reactive({
  orange: false,
  purple: false,
  black: false,
  yellow: false,
})

onMounted(() => {
  // Staggered entrance
  const delays: [string, number][] = [
    ['orange', 0], ['purple', 100], ['black', 200], ['yellow', 300],
  ]
  for (const [key, delay] of delays) {
    setTimeout(() => { charAnimated[key] = true }, delay)
  }
  setTimeout(() => { entranceDone.value = true }, 1000)
})

// ==================== DOM refs ====================
const containerRef = ref<HTMLElement>()
const orangeRef = ref<HTMLElement>()
const purpleRef = ref<HTMLElement>()
const blackRef = ref<HTMLElement>()
const yellowRef = ref<HTMLElement>()

// 眼球 refs
const orangeEye1Ref = ref<HTMLElement>()
const orangeEye2Ref = ref<HTMLElement>()
const yellowEye1Ref = ref<HTMLElement>()
const yellowEye2Ref = ref<HTMLElement>()
const purpleEye1Ref = ref<HTMLElement>()
const purpleEye2Ref = ref<HTMLElement>()
const blackEye1Ref = ref<HTMLElement>()
const blackEye2Ref = ref<HTMLElement>()

// ==================== 鼠标位置 ====================
const mouseX = ref(0)
const mouseY = ref(0)

// ==================== 角色位置 ====================
interface CharPos { faceX: number; faceY: number; bodySkew: number }
const charPos = reactive<Record<string, CharPos>>({
  orange:  { faceX: 0, faceY: 0, bodySkew: 0 },
  purple:  { faceX: 0, faceY: 0, bodySkew: 0 },
  black:   { faceX: 0, faceY: 0, bodySkew: 0 },
  yellow:  { faceX: 0, faceY: 0, bodySkew: 0 },
})

// ==================== 瞳孔偏移 ====================
const pupilOffset = reactive<Record<string, { x: number; y: number }>>({})
;['orange-0','orange-1','purple-0','purple-1','black-0','black-1','yellow-0','yellow-1'].forEach(k => {
  if (!(k in pupilOffset)) pupilOffset[k] = { x: 0, y: 0 }
})

// ==================== 状态 ====================
const isPurpleBlinking = ref(false)
const isBlackBlinking = ref(false)
const isLookingAtEachOther = ref(false)
const isPurplePeeking = ref(false)
const shakeOffset = ref(0)
let shakeRafId = 0

function triggerShake() {
  if (shakeRafId) cancelAnimationFrame(shakeRafId)
  const start = performance.now()
  const duration = 500
  function tick(now: number) {
    const elapsed = now - start
    if (elapsed >= duration) { shakeOffset.value = 0; shakeRafId = 0; return }
    const t = elapsed / duration
    const decay = 1 - t
    shakeOffset.value = Math.sin(t * Math.PI * 6) * 8 * decay
    shakeRafId = requestAnimationFrame(tick)
  }
  shakeRafId = requestAnimationFrame(tick)
}

// ==================== 派生状态 ====================
const isHidingPassword = computed(() => props.passwordLength > 0 && !props.showPassword)

// ==================== 位置计算 ====================
function calculatePosition(el: HTMLElement) {
  const rect = el.getBoundingClientRect()
  const centerX = rect.left + rect.width / 2
  const centerY = rect.top + rect.height / 3
  const faceX = clamp(-15, 15, (mouseX.value - centerX) / 20)
  const faceY = clamp(-10, 10, (mouseY.value - centerY) / 30)
  const bodySkew = clamp(-6, 6, -(mouseX.value - centerX) / 120)
  return { faceX, faceY, bodySkew }
}

function calcPupil(eyeEl: HTMLElement, maxDist: number) {
  const rect = eyeEl.getBoundingClientRect()
  const cx = rect.left + rect.width / 2
  const cy = rect.top + rect.height / 2
  const dx = mouseX.value - cx
  const dy = mouseY.value - cy
  const dist = Math.min(Math.sqrt(dx * dx + dy * dy), maxDist)
  const angle = Math.atan2(dy, dx)
  return { x: Math.cos(angle) * dist, y: Math.sin(angle) * dist }
}

// ==================== rAF 节流 ====================
let rafPending = false
let rafId = 0

function updateAllPositions() {
  rafPending = false; rafId = 0
  const refs: Record<string, HTMLElement | undefined> = {
    orange: orangeRef.value, purple: purpleRef.value,
    black: blackRef.value, yellow: yellowRef.value,
  }
  for (const key of Object.keys(refs)) {
    if (refs[key]) charPos[key] = calculatePosition(refs[key]!)
  }
  const eyeDefs: [string, HTMLElement | undefined, number][] = [
    ['orange-0', orangeEye1Ref.value, 5], ['orange-1', orangeEye2Ref.value, 5],
    ['purple-0', purpleEye1Ref.value, 5], ['purple-1', purpleEye2Ref.value, 5],
    ['black-0', blackEye1Ref.value, 4],   ['black-1', blackEye2Ref.value, 4],
    ['yellow-0', yellowEye1Ref.value, 5], ['yellow-1', yellowEye2Ref.value, 5],
  ]
  for (const [key, el, maxDist] of eyeDefs) {
    if (el) pupilOffset[key] = calcPupil(el, maxDist)
  }
}

function onMouseMove(e: MouseEvent) {
  mouseX.value = e.clientX; mouseY.value = e.clientY
  if (!rafPending) { rafPending = true; rafId = requestAnimationFrame(updateAllPositions) }
}

// ==================== 眨眼 ====================
let purpleBlinkTimer: ReturnType<typeof setTimeout> | null = null
let blackBlinkTimer: ReturnType<typeof setTimeout> | null = null

function schedulePurpleBlink() {
  purpleBlinkTimer = setTimeout(() => {
    isPurpleBlinking.value = true
    setTimeout(() => { isPurpleBlinking.value = false; schedulePurpleBlink() }, 150)
  }, 3000 + Math.random() * 4000)
}
function scheduleBlackBlink() {
  blackBlinkTimer = setTimeout(() => {
    isBlackBlinking.value = true
    setTimeout(() => { isBlackBlinking.value = false; scheduleBlackBlink() }, 150)
  }, 3000 + Math.random() * 4000)
}

// ==================== isTyping → 互看 ====================
let lookingTimer: ReturnType<typeof setTimeout> | null = null
watch(() => props.isTyping, (typing) => {
  if (typing) {
    isLookingAtEachOther.value = true
    if (lookingTimer) clearTimeout(lookingTimer)
    lookingTimer = setTimeout(() => { isLookingAtEachOther.value = false }, 800)
  } else {
    isLookingAtEachOther.value = false
    if (lookingTimer) clearTimeout(lookingTimer)
  }
})

// ==================== 密码可见 → 偷看 ====================
let peekTimer: ReturnType<typeof setTimeout> | null = null
watch([() => props.showPassword, () => props.passwordLength], ([show, len]) => {
  if (len > 0 && show) { schedulePeek() }
  else { isPurplePeeking.value = false; if (peekTimer) clearTimeout(peekTimer) }
})
function schedulePeek() {
  if (props.passwordLength === 0 || !props.showPassword) return
  peekTimer = setTimeout(() => {
    isPurplePeeking.value = true
    setTimeout(() => { isPurplePeeking.value = false; schedulePeek() }, 800)
  }, 2000 + Math.random() * 3000)
}

// ==================== 错误反应 ====================
watch(() => props.isError, (err) => {
  if (err) {
    triggerShake()
    setTimeout(() => emit('errorDone'), 600)
  }
})

// ==================== 生命周期 ====================
onMounted(() => {
  window.addEventListener('mousemove', onMouseMove, { passive: true })
  schedulePurpleBlink(); scheduleBlackBlink()
})
onUnmounted(() => {
  window.removeEventListener('mousemove', onMouseMove)
  ;[purpleBlinkTimer, blackBlinkTimer, lookingTimer, peekTimer].forEach(t => { if (t) clearTimeout(t) })
  if (rafId) { cancelAnimationFrame(rafId); rafId = 0 }
  if (shakeRafId) { cancelAnimationFrame(shakeRafId); shakeRafId = 0 }
})

// ==================== 各角色计算函数 ====================

// -- Orange --
function orangeEyePos() {
  const p = charPos.orange
  if (props.showPassword && props.passwordLength > 0) return { left: 50, top: 85 }
  return { left: 82 + p.faceX, top: 90 + p.faceY }
}
function orangeMouthPos() {
  const p = charPos.orange
  if (props.showPassword && props.passwordLength > 0) return { left: 90, top: 145 }
  return { left: 100 + p.faceX, top: 145 + p.faceY }
}
function orangeForceLook() {
  if (props.showPassword && props.passwordLength > 0) return { x: -5, y: -4 }
  return null
}
function orangeTransform() {
  const p = charPos.orange
  const so = shakeOffset.value ? ` translateX(${shakeOffset.value}px)` : ''
  if (props.showPassword && props.passwordLength > 0) return `skewX(0deg)${so}`
  return `skewX(${p.bodySkew}deg)${so}`
}

// -- Purple --
function purpleHeight() {
  return (props.isTyping || isHidingPassword.value) ? 440 : 400
}
function purpleEyePos() {
  const p = charPos.purple
  if (props.showPassword && props.passwordLength > 0) return { left: 20, top: 35 }
  if (isLookingAtEachOther.value) return { left: 55, top: 65 }
  return { left: 45 + p.faceX, top: 40 + p.faceY }
}
function purpleMouthPos() {
  const p = charPos.purple
  if (isLookingAtEachOther.value) return { left: 80, top: 95 }
  if (props.showPassword && props.passwordLength > 0) return { left: 45, top: 65 }
  return { left: 70 + p.faceX, top: 85 + p.faceY }
}
function purpleForceLook() {
  if (props.showPassword && props.passwordLength > 0 && isPurplePeeking.value) return { x: 4, y: 5 }
  if (props.showPassword && props.passwordLength > 0) return { x: -4, y: -4 }
  if (isLookingAtEachOther.value) return { x: 3, y: 4 }
  return null
}
function purpleTransform() {
  const p = charPos.purple
  const so = shakeOffset.value ? ` translateX(${shakeOffset.value}px)` : ''
  if (props.showPassword && props.passwordLength > 0) return `skewX(0deg)${so}`
  if (props.isTyping || isHidingPassword.value) return `skewX(${p.bodySkew - 12}deg) translateX(40px)${so}`
  return `skewX(${p.bodySkew}deg)${so}`
}

// -- Black --
function blackEyePos() {
  const p = charPos.black
  if (props.showPassword && props.passwordLength > 0) return { left: 10, top: 28 }
  if (isLookingAtEachOther.value) return { left: 32, top: 12 }
  return { left: 26 + p.faceX, top: 32 + p.faceY }
}
function blackForceLook() {
  if (props.showPassword && props.passwordLength > 0) return { x: -4, y: -4 }
  if (isLookingAtEachOther.value) return { x: 0, y: -4 }
  return null
}
function blackTransform() {
  const p = charPos.black
  const so = shakeOffset.value ? ` translateX(${shakeOffset.value}px)` : ''
  if (props.showPassword && props.passwordLength > 0) return `skewX(0deg)${so}`
  if (isLookingAtEachOther.value) return `skewX(${p.bodySkew * 1.5 + 10}deg) translateX(20px)${so}`
  if (props.isTyping || isHidingPassword.value) return `skewX(${p.bodySkew * 1.5}deg)${so}`
  return `skewX(${p.bodySkew}deg)${so}`
}

// -- Yellow --
function yellowEyePos() {
  const p = charPos.yellow
  if (props.showPassword && props.passwordLength > 0) return { left: 20, top: 35 }
  return { left: 52 + p.faceX, top: 40 + p.faceY }
}
function yellowMouthPos() {
  const p = charPos.yellow
  if (props.showPassword && props.passwordLength > 0) return { left: 10, top: 88 }
  return { left: 40 + p.faceX, top: 88 + p.faceY }
}
function yellowForceLook() {
  if (props.showPassword && props.passwordLength > 0) return { x: -5, y: -4 }
  return null
}
function yellowTransform() {
  const p = charPos.yellow
  const so = shakeOffset.value ? ` translateX(${shakeOffset.value}px)` : ''
  if (props.showPassword && props.passwordLength > 0) return `skewX(0deg)${so}`
  return `skewX(${p.bodySkew}deg)${so}`
}

function getPupilStyle(eyeKey: string, forceLook: { x: number; y: number } | null) {
  if (forceLook) return { transform: `translate(${forceLook.x}px, ${forceLook.y}px)` }
  const po = pupilOffset[eyeKey]
  return po ? { transform: `translate(${po.x}px, ${po.y}px)` } : {}
}

function eyeGroupStyle(pos: { left: number; top: number }, transition: string) {
  return {
    position: 'absolute' as const,
    left: `${pos.left}px`,
    top: `${pos.top}px`,
    display: 'flex',
    gap: '8px',
    transition: `all ${transition}`,
  }
}
</script>

<template>
  <div ref="containerRef" class="ac">
    <!-- ====== Orange: 宽半圆 z=3 ====== -->
    <div
      ref="orangeRef"
      class="ac__char"
      :class="{ 'ac__char--enter': charAnimated.orange }"
      :style="{
        left: '0px', width: '240px', height: '200px',
        background: '#FF9B6B', borderRadius: '120px 120px 0 0', zIndex: 3,
        transform: orangeTransform(),
      }"
    >
      <div :style="eyeGroupStyle(orangeEyePos(), '200ms ease-out')">
        <div ref="orangeEye1Ref" class="ac__pupil-dot" :style="getPupilStyle('orange-0', orangeForceLook())" />
        <div ref="orangeEye2Ref" class="ac__pupil-dot" :style="getPupilStyle('orange-1', orangeForceLook())" />
      </div>
      <!-- Orange 嘴巴 - 黑色实心扇形 -->
      <div
        :style="{
          position: 'absolute',
          left: `${orangeMouthPos().left}px`, top: `${orangeMouthPos().top}px`,
          width: '24px', height: '12px',
          background: '#2D2D2D',
          borderRadius: '0 0 12px 12px',
          transition: 'all 200ms ease-out',
        }"
      />
    </div>

    <!-- ====== Purple: 高矩形 z=1, EyeBall ====== -->
    <div
      ref="purpleRef"
      class="ac__char"
      :class="{ 'ac__char--enter': charAnimated.purple }"
      :style="{
        left: '70px', width: '180px',
        height: `${purpleHeight()}px`,
        background: '#6C3FF5', borderRadius: '10px 10px 0 0', zIndex: 1,
        transform: purpleTransform(),
      }"
    >
      <div :style="eyeGroupStyle(purpleEyePos(), '700ms ease-in-out')">
        <div ref="purpleEye1Ref" class="ac__eyeball"
          :style="{ width: `${18}px`, height: isPurpleBlinking ? '2px' : `${18}px` }">
          <div v-if="!isPurpleBlinking" class="ac__pupil-inner"
            :style="getPupilStyle('purple-0', purpleForceLook())" />
        </div>
        <div ref="purpleEye2Ref" class="ac__eyeball"
          :style="{ width: `${18}px`, height: isPurpleBlinking ? '2px' : `${18}px` }">
          <div v-if="!isPurpleBlinking" class="ac__pupil-inner"
            :style="getPupilStyle('purple-1', purpleForceLook())" />
        </div>
      </div>
      <!-- Purple 嘴巴 - 黑色竖线 -->
      <div
        :style="{
          position: 'absolute',
          left: `${purpleMouthPos().left}px`, top: `${purpleMouthPos().top}px`,
          width: '4px', height: '14px', background: '#2D2D2D',
          borderRadius: '2px',
          transition: 'all 700ms ease-in-out',
        }"
      />
    </div>

    <!-- ====== Black: 中矩形 z=2, EyeBall ====== -->
    <div
      ref="blackRef"
      class="ac__char"
      :class="{ 'ac__char--enter': charAnimated.black }"
      :style="{
        left: '240px', width: '120px', height: '310px',
        background: '#2D2D2D', borderRadius: '8px 8px 0 0', zIndex: 2,
        transform: blackTransform(),
      }"
    >
      <div :style="eyeGroupStyle(blackEyePos(), '700ms ease-in-out')">
        <div ref="blackEye1Ref" class="ac__eyeball"
          :style="{ width: `${16}px`, height: isBlackBlinking ? '2px' : `${16}px` }">
          <div v-if="!isBlackBlinking" class="ac__pupil-inner ac__pupil-inner--sm"
            :style="getPupilStyle('black-0', blackForceLook())" />
        </div>
        <div ref="blackEye2Ref" class="ac__eyeball"
          :style="{ width: `${16}px`, height: isBlackBlinking ? '2px' : `${16}px` }">
          <div v-if="!isBlackBlinking" class="ac__pupil-inner ac__pupil-inner--sm"
            :style="getPupilStyle('black-1', blackForceLook())" />
        </div>
      </div>
    </div>

    <!-- ====== Yellow: 矮半圆 z=4 ====== -->
    <div
      ref="yellowRef"
      class="ac__char"
      :class="{ 'ac__char--enter': charAnimated.yellow }"
      :style="{
        left: '310px', width: '140px', height: '230px',
        background: '#E8D754', borderRadius: '70px 70px 0 0', zIndex: 4,
        transform: yellowTransform(),
      }"
    >
      <div :style="eyeGroupStyle(yellowEyePos(), '200ms ease-out')">
        <div ref="yellowEye1Ref" class="ac__pupil-dot" :style="getPupilStyle('yellow-0', yellowForceLook())" />
        <div ref="yellowEye2Ref" class="ac__pupil-dot" :style="getPupilStyle('yellow-1', yellowForceLook())" />
      </div>
      <!-- Yellow 嘴巴 -->
      <div
        :style="{
          position: 'absolute',
          left: `${yellowMouthPos().left}px`, top: `${yellowMouthPos().top}px`,
          width: '80px', height: '4px', background: '#2D2D2D',
          borderRadius: '9999px',
          transition: 'all 200ms ease-out',
        }"
      />
    </div>
  </div>
</template>

<style scoped lang="scss">
.ac {
  position: relative;
  width: 550px;
  height: 400px;

  &__char {
    position: absolute;
    bottom: 0;
    transition: all 700ms ease-in-out;
    opacity: 0;
    transform: translateY(60px);

    // 入场
    &--enter {
      animation: char-entrance 0.6s ease-out forwards;
    }
  }

  // 简单瞳孔 (Orange, Yellow)
  &__pupil-dot {
    width: 12px;
    height: 12px;
    border-radius: 50%;
    background: #2D2D2D;
    transition: transform 0.1s ease-out;
  }

  // 白眼球容器 (Purple, Black)
  &__eyeball {
    border-radius: 50%;
    background: #fff;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: all 150ms;
    overflow: hidden;
  }

  // 眼球内瞳孔
  &__pupil-inner {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #2D2D2D;
    transition: transform 0.1s ease-out;

    &--sm {
      width: 6px;
      height: 6px;
    }
  }
}
</style>
