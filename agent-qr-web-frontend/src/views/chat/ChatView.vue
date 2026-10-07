<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { chatApi } from '@/api/chat'
import { useAuthStore, resolveAvailableDomains } from '@/stores/auth'
import { formatDomain } from '@/utils/format'
import {
  useWebSocket,
  describeDocumentProgress,
  describeOpsAlert,
  type DocumentProgressMessage,
  type OpsAlertMessage,
} from '@/composables/useWebSocket'
import { useSpeechRecognition } from '@/composables/useSpeechRecognition'
import type { Conversation, Message, SourceVO } from '@/types'
import ConversationList from '@/components/chat/ConversationList.vue'
import MessageBubble from '@/components/chat/MessageBubble.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import FlatIllustration from '@/components/common/FlatIllustration.vue'

const { t } = useI18n()

// ========== 本地消息类型 ==========
interface LocalMessage {
  id: number | string
  role: 'user' | 'assistant'
  content: string
  sources?: SourceVO[]
  loading?: boolean
  streaming?: boolean
  feedback?: 'positive' | 'negative' | null
}

// ========== 状态 ==========
const authStore = useAuthStore()
const conversations = ref<Conversation[]>([])
const conversationsLoading = ref(false)
const activeConversationId = ref<number | null>(null)
const messages = ref<LocalMessage[]>([])
const sending = ref(false)
const messagesLoading = ref(false)
const deletingId = ref<number | null>(null)
const sidebarCollapsed = ref(false)
const messagesContainerRef = ref<HTMLElement | null>(null)
const abortController = ref<AbortController | null>(null)

// ========== P3 WebSocket 连接 ==========
/**
 * ★ 批次 11 · R46：接线消息消费。
 *
 * 批次 10.2 打通了服务端通道（文档进度按用户队列投递、运维告警广播给管理员），
 * 但这里一直没传回调——消息到了浏览器就被丢弃（"订阅了却无人消费"）。
 * 文档进度用轻量 toast；运维告警用右下角常驻通知（需人工确认，不自动消失）。
 *
 * 管理员判定在 setup 时取一次快照：`App.vue` 的回源用户信息（R19③）若晚于本次订阅，
 * 首次连接可能不订阅运维频道，刷新页面即恢复（不影响文档进度队列）。
 */
const ws = useWebSocket(undefined, {
  onDocumentProgress: handleDocumentProgress,
  onOpsAlert: handleOpsAlert,
  subscribeOpsAlerts: authStore.isAdmin,
})
const wsAvailable = ref(false)

/** 文档进度：终态提示（中间态由 describeDocumentProgress 过滤掉） */
function handleDocumentProgress(payload: DocumentProgressMessage) {
  const notice = describeDocumentProgress(payload)
  if (!notice) return
  ElMessage({
    type: notice.type,
    message: t(notice.i18nKey, notice.params),
    duration: 4000,
    showClose: true,
  })
}

/** 运维告警：常驻通知（duration 0 = 不自动消失），仅管理员订阅得到 */
function handleOpsAlert(payload: OpsAlertMessage) {
  const notice = describeOpsAlert(payload)
  ElNotification({
    type: notice.type,
    title: t('chat.ws.opsAlertTitle'),
    message: t(notice.i18nKey, notice.params),
    duration: 0,
    position: 'bottom-right',
  })
}

// ========== P3 语音识别 ==========
const speech = useSpeechRecognition()
const voiceInputText = ref('')

// ========== 域选择器选项 ==========
/**
 * ★ 批次 03 联动修复：后端强制要求非空 domain，因此这里只暴露用户真正可用的域：
 * - 普通用户 → 其 allowedDomains（无授权域则为空 → 输入区禁用并提示）
 * - admin    → allowedDomains 为空时回退到全量域（后端 ABAC 对 admin 直通）
 *
 * 返回列表非空时，ChatInput 默认选中首项，因此传给后端的 domain 一定非空。
 */
const availableDomains = computed(() =>
  resolveAvailableDomains(authStore.user?.allowedDomains, authStore.isAdmin).map((d) => ({
    value: d,
    label: formatDomain(d),
  })),
)

// ========== 工具函数 ==========
function parseSources(sourcesStr: string): SourceVO[] {
  if (!sourcesStr) return []
  try {
    const parsed = JSON.parse(sourcesStr)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

// ========== 加载会话列表 ==========
async function loadConversations() {
  conversationsLoading.value = true
  try {
    const res = await chatApi.listConversations()
    conversations.value = res.data || []
  } catch {
    conversations.value = []
  } finally {
    conversationsLoading.value = false
  }
}

// ========== 加载历史消息 ==========
async function loadMessages(conversationId: number) {
  messagesLoading.value = true
  try {
    const res = await chatApi.getMessages(conversationId)
    const rawMessages: Message[] = res.data || []
    messages.value = rawMessages.map((msg) => ({
      id: msg.id,
      role: msg.role,
      content: msg.content,
      sources: msg.role === 'assistant' ? parseSources(msg.sources) : undefined,
    }))
  } catch {
    messages.value = []
  } finally {
    messagesLoading.value = false
  }
}

// ========== 选中会话 ==========
async function handleSelectConversation(conversationId: number) {
  if (activeConversationId.value === conversationId) return
  activeConversationId.value = conversationId
  await loadMessages(conversationId)
  scrollToBottom()
}

// ========== 创建新会话 ==========
function handleCreateConversation() {
  activeConversationId.value = null
  messages.value = []
  sidebarCollapsed.value = false
}

// ========== 删除会话 ==========
async function handleDeleteConversation(conversationId: number) {
  try {
    await chatApi.deleteConversation(conversationId)
    conversations.value = conversations.value.filter((c) => c.id !== conversationId)

    // 如果删除的是当前选中的会话，回退到欢迎页
    if (activeConversationId.value === conversationId) {
      activeConversationId.value = null
      messages.value = []
    }
  } catch {
    // 错误已在拦截器中处理
  } finally {
    deletingId.value = null
  }
}

// ========== 发送消息（P2 SSE 流式） ==========
async function handleSend(content: string, domain?: string) {
  if (!content.trim() || sending.value) return

  // ★ 批次 03 联动修复：域为空时绝不发出请求。
  //   后端 /api/chat/ask/stream 强制校验 domain，缺失时返回业务码 400（HTTP 200），
  //   而 SSE 通过 fetchEventSource 只感知 HTTP 状态，用户界面会表现为"无任何响应"。
  if (!domain) {
    ElMessage.warning('请先选择业务域')
    return
  }

  const query = content.trim()
  const conversationId = activeConversationId.value

  // 1. 添加用户消息
  const userMsgId = -Date.now()
  messages.value.push({
    id: userMsgId,
    role: 'user',
    content: query,
  })

  // 2. 添加空 assistant 消息（流式状态）
  const assistantMsgId = -(Date.now() + 1)
  messages.value.push({
    id: assistantMsgId,
    role: 'assistant',
    content: '',
    streaming: true,
  })

  sending.value = true
  scrollToBottom()

  // 辅助函数：找到 assistant 消息的索引
  const findAssistantIndex = () => messages.value.findIndex((m) => m.id === assistantMsgId)

  try {
    // 3. 调用 SSE 流式接口（domain 已在入口处保证非空）
    const controller = chatApi.askStream(query, domain, conversationId ?? null, {
      onToken(token: string) {
        const idx = findAssistantIndex()
        if (idx !== -1) {
          messages.value[idx].content += token
          scrollToBottom()
        }
      },
      onDone(data: { answer: string; conversationId: number; messageId: number; sources: SourceVO[] }) {
        const idx = findAssistantIndex()
        if (idx !== -1) {
          // ★ 优先使用后端返回的完整答案，避免 token 事件丢失导致正文为空
          const answerContent = data.answer || messages.value[idx].content
          const hasSources = data.sources && data.sources.length > 0

          if (!answerContent && !hasSources) {
            // 空检索结果
            messages.value[idx] = {
              id: data.messageId || assistantMsgId,
              role: 'assistant',
              content: '知识库中暂无相关信息，请联系管理员上传相关文档',
              sources: [],
              streaming: false,
            }
          } else {
            messages.value[idx] = {
              id: data.messageId || assistantMsgId,
              role: 'assistant',
              content: answerContent,
              sources: data.sources || [],
              streaming: false,
            }
          }
        }

        // 如果是新会话，更新 activeConversationId 并刷新列表
        if (!activeConversationId.value && data.conversationId) {
          activeConversationId.value = data.conversationId
          loadConversations()
        }

        sending.value = false
        abortController.value = null
        scrollToBottom()
      },
      onError(error: string) {
        // 后端发送的 SSE error 事件数据是 JSON 字符串（如 {"message":"..."}），需解析
        let errorMsg = '抱歉，请求失败，请重试'
        try {
          const parsed = JSON.parse(error)
          errorMsg = parsed.message || errorMsg
        } catch {
          errorMsg = error || errorMsg
        }

        const idx = findAssistantIndex()
        if (idx !== -1) {
          const currentContent = messages.value[idx].content
          messages.value[idx] = {
            id: assistantMsgId,
            role: 'assistant',
            content: currentContent
              ? currentContent + '\n\n_（生成出错，请重试）_'
              : errorMsg,
            sources: [],
            streaming: false,
          }
        }

        sending.value = false
        abortController.value = null
        scrollToBottom()
      },
    })

    abortController.value = controller
  } catch {
    const idx = findAssistantIndex()
    if (idx !== -1) {
      messages.value[idx] = {
        id: assistantMsgId,
        role: 'assistant',
        content: '抱歉，请求失败，请重试',
        sources: [],
        streaming: false,
      }
    }

    sending.value = false
    abortController.value = null
    scrollToBottom()
  }
}

// ========== 停止生成 ==========
function handleStopGeneration() {
  abortController.value?.abort()
  abortController.value = null

  // 在最后一条 assistant 消息末尾追加停止提示
  const lastMsg = messages.value[messages.value.length - 1]
  if (lastMsg && lastMsg.role === 'assistant' && lastMsg.streaming) {
    lastMsg.content += '\n\n_（已停止生成）_'
    lastMsg.streaming = false
  }

  sending.value = false
  scrollToBottom()
}

// ========== 反馈评价 ==========
async function handleFeedback(messageId: number | string, type: 'positive' | 'negative') {
  // 找到对应消息
  const msg = messages.value.find((m) => m.id === messageId)
  if (!msg || typeof msg.id !== 'number') return

  if (type === 'positive') {
    try {
      await chatApi.submitFeedback(msg.id, 'positive')
      msg.feedback = 'positive'
      ElMessage.success(t('chat.feedback.thanks'))
    } catch {
      // 错误已在拦截器中处理
    }
  } else {
    try {
      const { value: reason } = await ElMessageBox.prompt(
        t('chat.feedback.reason'),
        t('chat.feedback.title'),
        {
          confirmButtonText: t('common.submit'),
          cancelButtonText: t('common.cancel'),
          inputPlaceholder: t('common.optional'),
        },
      )
      await chatApi.submitFeedback(msg.id, 'negative', reason || undefined)
      msg.feedback = 'negative'
      ElMessage.success(t('chat.feedback.thanks'))
    } catch {
      // 用户取消操作
    }
  }
}

// ========== 滚动到底部 ==========
function scrollToBottom() {
  nextTick(() => {
    const container = messagesContainerRef.value
    if (container) {
      container.scrollTop = container.scrollHeight
    }
  })
}

// ========== 切换会话时滚动 ==========
watch(activeConversationId, () => {
  if (activeConversationId.value !== null) {
    nextTick(() => scrollToBottom())
  }
})

// ========== 初始化 ==========
onMounted(async () => {
  loadConversations()
  // P3: 尝试建立 WebSocket 连接
  if (authStore.accessToken) {
    try {
      await ws.connect(authStore.accessToken)
      wsAvailable.value = ws.isAvailable()
    } catch {
      console.log('[ChatView] WebSocket 不可用，降级使用 SSE')
    }
  }
})

// ========== 销毁时中止未完成的请求 ==========
onUnmounted(() => {
  abortController.value?.abort()
  // P3: 断开 WebSocket
  ws.disconnect()
})
</script>

<template>
  <div class="chat-view">
    <!-- 侧边栏切换按钮 -->
    <div
      class="chat-view__toggle"
      :class="{ 'chat-view__toggle--collapsed': sidebarCollapsed }"
      @click="sidebarCollapsed = !sidebarCollapsed"
    >
      <span v-if="sidebarCollapsed">☰</span>
      <span v-else>✕</span>
    </div>

    <!-- 左侧会话列表 -->
    <div class="chat-view__sidebar" :class="{ 'chat-view__sidebar--collapsed': sidebarCollapsed }">
      <ConversationList
        :conversations="conversations"
        :active-id="activeConversationId ?? undefined"
        :loading="conversationsLoading"
        v-model:deleting-id="deletingId"
        @select="handleSelectConversation"
        @delete="handleDeleteConversation"
        @create="handleCreateConversation"
      />
    </div>

    <!-- 右侧聊天区 -->
    <div class="chat-view__main">
      <!-- 欢迎语（无会话且无消息时显示） -->
      <div v-if="activeConversationId === null && messages.length === 0" class="chat-view__welcome">
        <div class="chat-view__welcome-icon">
          <FlatIllustration name="empty-chat" />
        </div>
        <h2 class="chat-view__welcome-title">{{ $t('auth.welcomeSubtitle') }}</h2>
        <p class="chat-view__welcome-desc">{{ $t('chat.emptyMessage') }}</p>
        <!-- P3 连接状态指示器 -->
        <div class="chat-view__connection-status">
          <span
            class="connection-dot"
            :class="{
              'connection-dot--connected': ws.connectionState.value === 'connected',
              'connection-dot--connecting': ws.connectionState.value === 'connecting',
              'connection-dot--disconnected': ws.connectionState.value === 'disconnected'
            }"
          />
          <span class="connection-text">
            {{ ws.connectionState.value === 'connected' ? $t('chat.connectionStatus.connected') :
               ws.connectionState.value === 'connecting' ? $t('chat.connectionStatus.reconnecting') :
               $t('chat.connectionStatus.disconnected') }}
          </span>
        </div>
      </div>

      <!-- 聊天消息区 -->
      <template v-else>
        <div ref="messagesContainerRef" class="chat-view__messages">
          <div v-if="messagesLoading" class="chat-view__messages-loading">
            <el-skeleton :rows="3" animated />
          </div>
          <template v-else>
            <div v-if="messages.length === 0" class="chat-view__messages-empty">
              <el-empty :description="$t('chat.emptyMessage')" />
            </div>
            <MessageBubble
              v-for="msg in messages"
              :key="msg.id"
              :role="msg.role"
              :content="msg.content"
              :sources="msg.sources"
              :loading="msg.loading"
              :streaming="msg.streaming"
              :feedback="msg.feedback"
              @feedback="(type) => handleFeedback(msg.id, type)"
            />
          </template>
        </div>
      </template>

      <!-- 底部输入区 -->
      <ChatInput
        :loading="sending"
        :disabled="false"
        :domains="availableDomains"
        @send="handleSend"
        @stop="handleStopGeneration"
      />
    </div>
  </div>
</template>

<style scoped lang="scss">
.chat-view {
  display: flex;
  height: 100%;
  position: relative;

  &__toggle {
    position: absolute;
    top: 12px;
    left: 284px;
    z-index: 10;
    width: 28px;
    height: 28px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: #fff;
    border: 1px solid $border-color;
    border-radius: 4px;
    cursor: pointer;
    font-size: 14px;
    color: $text-regular;
    transition: left var(--transition-base);

    &--collapsed {
      left: 12px;
    }
  }

  &__sidebar {
    width: 280px;
    flex-shrink: 0;
    height: 100%;
    overflow: hidden;
    transition: width var(--transition-base);

    &--collapsed {
      width: 0;
    }
  }

  &__main {
    flex: 1;
    display: flex;
    flex-direction: column;
    height: 100%;
    min-width: 0;
  }

  &__welcome {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 40px 20px;
  }

  &__welcome-icon {
    font-size: 64px;
    margin-bottom: 16px;
  }

  &__welcome-title {
    font-size: var(--font-size-2xl);
    color: $text-primary;
    margin-bottom: var(--space-2);
  }

  &__welcome-desc {
    font-size: $font-size-base;
    color: $text-secondary;
  }

  &__messages {
    flex: 1;
    overflow-y: auto;
    padding: 16px 20px;
  }

  &__messages-loading {
    padding: 20px;
  }

  &__messages-empty {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100%;
  }

  // P3 连接状态指示器
  &__connection-status {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 12px;
    font-size: 12px;
    color: $text-secondary;
  }
}

// P3 连接状态点
.connection-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;

  &--connected {
    background-color: #67c23a;
    box-shadow: 0 0 4px #67c23a;
  }

  &--connecting {
    background-color: #e6a23c;
    animation: pulse 1.5s infinite;
  }

  &--disconnected {
    background-color: #f56c6c;
  }
}

.connection-text {
  color: $text-secondary;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

// P3 移动端适配
@media (max-width: 767px) {
  .chat-view {
    &__sidebar {
      width: 0;
    }

    &__toggle {
      left: 12px;
    }

    &__welcome {
      padding: 20px 16px;
    }

    &__welcome-icon {
      font-size: 48px;
    }

    &__welcome-title {
      font-size: var(--font-size-lg);
    }

    &__messages {
      padding: 12px;
    }
  }
}
</style>
