<script setup>
import { nextTick, ref } from 'vue'
import request from '../utils/request'

const open = ref(false)
const input = ref('')
const sending = ref(false)
const error = ref('')
const conversationId = ref('')
const messages = ref([
  { role: 'assistant', content: '你好，我可以帮你查询平台规则、秒杀活动和你的订单状态。' }
])
const messageList = ref(null)

function scrollToBottom() {
  nextTick(() => {
    if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
  })
}

async function ensureConversation() {
  if (conversationId.value) return conversationId.value
  const result = await request.post('/customer-service/conversations')
  conversationId.value = result.data.conversationId
  return conversationId.value
}

function appendEvent(raw) {
  if (!raw.startsWith('data:')) return
  try {
    const event = JSON.parse(raw.slice(5).trim())
    if (event.type === 'token') {
      const last = messages.value[messages.value.length - 1]
      if (!last || last.role !== 'assistant' || !last.streaming) {
        messages.value.push({ role: 'assistant', content: event.content || '', streaming: true })
      } else {
        last.content += event.content || ''
      }
    } else if (event.type === 'error') {
      error.value = event.message || '客服暂时不可用'
      const last = messages.value[messages.value.length - 1]
      if (last?.streaming && !last.content) messages.value.pop()
    } else if (event.type === 'done') {
      const last = messages.value[messages.value.length - 1]
      if (last) delete last.streaming
    }
  } catch (e) { /* ignore incomplete SSE frames */ }
  scrollToBottom()
}

async function sendMessage() {
  const content = input.value.trim()
  if (!content || sending.value) return
  input.value = ''
  error.value = ''
  messages.value.push({ role: 'user', content })
  messages.value.push({ role: 'assistant', content: '', streaming: true })
  sending.value = true
  scrollToBottom()
  try {
    const id = await ensureConversation()
    const token = localStorage.getItem('token')
    const response = await fetch(`/api/customer-service/conversations/${id}/messages/stream`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ message: content })
    })
    if (!response.ok || !response.body) throw new Error('客服服务暂时不可用')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const frames = buffer.split('\n\n')
      buffer = frames.pop() || ''
      frames.forEach(appendEvent)
    }
    if (buffer) appendEvent(buffer)
  } catch (e) {
    const last = messages.value[messages.value.length - 1]
    if (last?.streaming) messages.value.pop()
    error.value = e.message || '客服暂时不可用'
  } finally {
    const last = messages.value[messages.value.length - 1]
    if (last?.streaming) delete last.streaming
    sending.value = false
    scrollToBottom()
  }
}

function handleKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendMessage()
  }
}
</script>

<template>
  <div class="customer-service">
    <section v-if="open" class="chat-panel" aria-label="智能客服">
      <header class="chat-header">
        <div><strong>智能客服</strong><small>规则、活动和订单查询</small></div>
        <button class="close-button" aria-label="关闭客服" @click="open=false">×</button>
      </header>
      <div ref="messageList" class="message-list">
        <div v-for="(message, index) in messages" :key="index" class="message" :class="message.role">
          {{ message.content || '正在查询...' }}
        </div>
        <div v-if="error" class="chat-error">{{ error }}</div>
      </div>
      <form class="chat-form" @submit.prevent="sendMessage">
        <textarea v-model="input" rows="2" maxlength="2000" placeholder="例如：我的订单为什么还没发货？" @keydown="handleKeydown" />
        <button class="btn btn-primary btn-sm" type="submit" :disabled="sending || !input.trim()">{{ sending ? '发送中' : '发送' }}</button>
      </form>
    </section>
    <button v-if="!open" class="chat-launcher" aria-label="打开智能客服" @click="open=true">客服</button>
  </div>
</template>

<style scoped>
.customer-service{position:fixed;right:22px;bottom:22px;z-index:150}.chat-launcher{width:58px;height:58px;border:0;border-radius:50%;background:var(--primary);color:#fff;font-weight:700;box-shadow:0 8px 24px rgba(0,0,0,.18);cursor:pointer}.chat-panel{width:350px;height:480px;background:var(--card-bg);border:1px solid var(--border);border-radius:10px;box-shadow:0 12px 40px rgba(0,0,0,.2);display:flex;flex-direction:column;overflow:hidden}.chat-header{display:flex;justify-content:space-between;align-items:center;padding:15px 16px;background:var(--primary);color:#fff}.chat-header strong,.chat-header small{display:block}.chat-header small{font-size:11px;opacity:.8;margin-top:3px}.close-button{border:0;background:none;color:#fff;font-size:24px;line-height:1;cursor:pointer}.message-list{flex:1;overflow-y:auto;padding:14px;background:var(--bg)}.message{max-width:84%;padding:9px 11px;margin-bottom:9px;border-radius:8px;font-size:13px;line-height:1.55;white-space:pre-wrap;word-break:break-word}.message.assistant{background:var(--card-bg);border:1px solid var(--border);margin-right:auto}.message.user{background:var(--primary);color:#fff;margin-left:auto}.chat-error{font-size:12px;color:var(--danger);padding:7px 0}.chat-form{display:flex;gap:8px;align-items:flex-end;padding:10px;border-top:1px solid var(--border);background:var(--card-bg)}.chat-form textarea{flex:1;resize:none;border:1px solid var(--border);border-radius:6px;padding:8px;font:inherit;font-size:13px;min-width:0}.chat-form textarea:focus{outline:2px solid var(--primary-light);border-color:var(--primary)}@media(max-width:600px){.customer-service{right:12px;bottom:12px}.chat-panel{width:min(350px,calc(100vw - 24px));height:70vh}}
</style>
