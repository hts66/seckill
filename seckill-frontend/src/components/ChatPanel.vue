<script setup>
import { ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { connectChat, sendChat, onChat, connected as chatConnected } from '../composables/useChat'
import { getOrderChat, readOrderChat, getAdminMessages, readAdminConversation } from '../api/chat'

const PAGE_SIZE = 30

const props = defineProps({
  // 用户端传 orderNo；客服端传 conversationId
  orderNo: { type: String, default: '' },
  conversationId: { type: Number, default: null },
  // 0=用户（买家） 1=客服
  selfType: { type: Number, default: 0 },
  title: { type: String, default: '在线客服' },
})

const messages = ref([])
const currentConvId = ref(null)
const input = ref('')
const loading = ref(false)
const loadingMore = ref(false)
const hasMore = ref(true)
const loadError = ref('')
const sendError = ref('')
const sendDisabled = ref(false)
const bodyRef = ref(null)

function scrollToBottom() {
  const el = bodyRef.value
  if (el) el.scrollTop = el.scrollHeight
}

function prependKeepingScroll(older) {
  const el = bodyRef.value
  const prevHeight = el?.scrollHeight || 0
  const prevTop = el?.scrollTop || 0
  // 按雪花 id 去重后前插（实时推送与历史拉取可能重叠）
  const existing = new Set(messages.value.map(m => m.id))
  messages.value = [...older.filter(m => !existing.has(m.id)), ...messages.value]
  return nextTick().then(() => {
    if (el) el.scrollTop = el.scrollHeight - prevHeight + prevTop
  })
}

async function load() {
  loadError.value = ''
  loading.value = true
  hasMore.value = true
  messages.value = []
  try {
    if (props.selfType === 1) {
      if (!props.conversationId) { return }
      currentConvId.value = props.conversationId
      const res = await getAdminMessages(props.conversationId, null, PAGE_SIZE)
      messages.value = res.data || []
      hasMore.value = messages.value.length >= PAGE_SIZE
      readAdminConversation(props.conversationId).catch(() => {})
      // 订阅该会话房间，之后新消息实时推送
      sendChat({ type: 'subscribe', conversationId: props.conversationId })
    } else {
      const res = await getOrderChat(props.orderNo, null, PAGE_SIZE)
      currentConvId.value = res.data?.conversationId || null
      messages.value = res.data?.messages || []
      hasMore.value = messages.value.length >= PAGE_SIZE
      readOrderChat(props.orderNo).catch(() => {})
    }
    await nextTick()
    scrollToBottom()
  } catch (e) {
    loadError.value = e.message || '消息加载失败'
  } finally {
    loading.value = false
  }
}

async function loadOlder() {
  if (loadingMore.value || !hasMore.value || messages.value.length === 0) return
  loadingMore.value = true
  try {
    const beforeId = messages.value[0].id
    let older = []
    if (props.selfType === 1) {
      const res = await getAdminMessages(props.conversationId, beforeId, PAGE_SIZE)
      older = res.data || []
    } else {
      const res = await getOrderChat(props.orderNo, beforeId, PAGE_SIZE)
      older = res.data?.messages || []
    }
    await prependKeepingScroll(older)
    if (older.length < PAGE_SIZE) hasMore.value = false
  } catch (e) {
    flashError(e.message || '历史消息加载失败')
  } finally {
    loadingMore.value = false
  }
}

function onMessage(data) {
  const cid = data.conversationId
  if (props.selfType === 1) {
    if (cid !== props.conversationId) return
  } else {
    // 用户端：首条消息时会话刚创建，currentConvId 为空也要认领
    if (currentConvId.value && cid !== currentConvId.value) return
    if (!currentConvId.value) currentConvId.value = cid
  }
  if (data.message) {
    if (messages.value.some(m => m.id === data.message.id)) return
    messages.value.push(data.message)
    nextTick(scrollToBottom)
  }
  // 用户正打开窗口，客服发来的消息直接清零未读
  if (props.selfType === 0 && data.message?.senderType === 1) {
    readOrderChat(props.orderNo).catch(() => {})
  }
}

function onError(data) {
  flashSendError(data?.message || '消息发送失败')
  sendDisabled.value = true
  setTimeout(() => { sendDisabled.value = false }, 1000)
}

let errorTimer = null
function flashError(msg) {
  loadError.value = msg
  clearTimeout(errorTimer)
  errorTimer = setTimeout(() => { loadError.value = '' }, 3000)
}

let sendErrorTimer = null
function flashSendError(msg) {
  sendError.value = msg
  clearTimeout(sendErrorTimer)
  sendErrorTimer = setTimeout(() => { sendError.value = '' }, 3000)
}

function formatTime(t) {
  return (t || '').slice(11, 16)
}

function send() {
  const content = input.value.trim()
  if (!content) return
  const payload = props.selfType === 1
    ? { type: 'chat', conversationId: props.conversationId, content }
    : { type: 'chat', orderNo: props.orderNo, content }
  if (sendChat(payload)) {
    input.value = ''
  } else {
    flashSendError('正在连接服务器，请稍候再发')
  }
}

let offMessage, offError
onMounted(() => {
  connectChat()
  offMessage = onChat('message', onMessage)
  offError = onChat('error', onError)
  load()
})
onUnmounted(() => { offMessage?.(); offError?.(); clearTimeout(errorTimer); clearTimeout(sendErrorTimer) })
watch(() => [props.conversationId, props.orderNo], () => load())
</script>

<template>
  <div class="chat-panel">
    <div class="chat-head">
      <span class="chat-title">💬 {{ title }}</span>
      <span class="chat-status" :class="{ online: chatConnected }">
        {{ chatConnected ? '在线' : '连接中…' }}
      </span>
    </div>

    <div ref="bodyRef" class="chat-body">
      <button
        v-if="hasMore && !loading && messages.length > 0"
        class="chat-load-more"
        :disabled="loadingMore"
        @click="loadOlder"
      >{{ loadingMore ? '加载中…' : '加载更早消息' }}</button>
      <div v-if="loading" class="chat-tip">加载中…</div>
      <div v-else-if="loadError" class="chat-tip err">{{ loadError }}</div>
      <div v-else-if="messages.length === 0" class="chat-tip">暂无消息，有问题随时咨询客服～</div>
      <div
        v-for="m in messages"
        :key="m.id"
        class="chat-row"
        :class="{ mine: m.senderType === selfType }"
      >
        <div class="bubble">
          <div class="bubble-content">{{ m.content }}</div>
          <div class="bubble-time">{{ formatTime(m.createdAt) }}</div>
        </div>
      </div>
    </div>

    <div class="chat-input-wrap">
      <div v-if="sendError" class="chat-send-error">{{ sendError }}</div>
      <div class="chat-input">
        <input
          v-model="input"
          class="input"
          placeholder="输入消息，回车发送"
          maxlength="1000"
          @keyup.enter="!sendDisabled && send()"
        />
        <button class="btn btn-primary btn-sm" :disabled="sendDisabled" @click="send">发送</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-panel {
  display: flex;
  flex-direction: column;
  height: 460px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
  background: var(--bg);
}
.chat-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  background: var(--card-bg);
  border-bottom: 1px solid var(--border);
}
.chat-title { font-weight: 600; font-size: 14px; }
.chat-status { font-size: 12px; color: var(--text-light); }
.chat-status.online { color: var(--success); }
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.chat-load-more {
  align-self: center;
  border: 1px solid var(--border);
  background: var(--card-bg);
  border-radius: 999px;
  padding: 4px 14px;
  font-size: 12px;
  color: var(--text-light);
  cursor: pointer;
}
.chat-load-more:disabled { opacity: 0.6; cursor: default; }
.chat-tip { text-align: center; color: var(--text-light); font-size: 13px; padding: 20px 0; }
.chat-tip.err { color: var(--danger); }
.chat-row { display: flex; }
.chat-row.mine { justify-content: flex-end; }
.bubble {
  max-width: 76%;
  padding: 8px 12px;
  border-radius: 12px;
  background: var(--card-bg);
  border: 1px solid var(--border);
  box-shadow: 0 1px 2px rgba(0,0,0,0.04);
}
.chat-row.mine .bubble {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
}
.bubble-content { font-size: 14px; line-height: 1.5; word-break: break-word; white-space: pre-wrap; }
.bubble-time { font-size: 11px; opacity: 0.7; margin-top: 3px; text-align: right; }
.chat-input-wrap {
  background: var(--card-bg);
  border-top: 1px solid var(--border);
}
.chat-send-error {
  padding: 6px 12px;
  font-size: 12px;
  color: var(--danger);
  background: rgba(220, 53, 69, 0.08);
  text-align: center;
}
.chat-input {
  display: flex;
  gap: 8px;
  padding: 10px;
}
.chat-input .input { flex: 1; margin: 0; }
</style>
