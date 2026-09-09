import { ref } from 'vue'

// 全局单例 WebSocket：用户端和客服端共用一条连接，支持同一账号多端在线。
export const connected = ref(false)
let ws = null
let reconnectTimer = null
let heartbeatTimer = null
let reconnectAttempts = 0
let manualClosed = false
const listeners = new Map() // event -> Set<callback>

function emit(event, data) {
  const set = listeners.get(event)
  if (set) set.forEach(fn => { try { fn(data) } catch (e) { console.error('chat listener error', e) } })
}

/** 订阅聊天事件：message / conversation / status / error；返回取消订阅函数。 */
export function onChat(event, fn) {
  if (!listeners.has(event)) listeners.set(event, new Set())
  listeners.get(event).add(fn)
  return () => offChat(event, fn)
}

export function offChat(event, fn) {
  listeners.get(event)?.delete(fn)
}

function wsUrl() {
  const token = localStorage.getItem('token') || ''
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  // 地址随当前页面推导，手机用电脑局域网 IP 访问时同样生效，无需写死端口
  return `${proto}://${location.host}/ws/chat?token=${encodeURIComponent(token)}`
}

function stopHeartbeat() {
  if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null }
}

function startHeartbeat() {
  stopHeartbeat()
  heartbeatTimer = setInterval(() => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      try { ws.send(JSON.stringify({ type: 'ping' })) } catch { /* ignored */ }
    }
  }, 30000)
}

function scheduleReconnect() {
  if (manualClosed || reconnectTimer) return
  const delay = Math.min(1000 * 2 ** reconnectAttempts, 15000)
  reconnectAttempts++
  reconnectTimer = setTimeout(() => { reconnectTimer = null; connectChat() }, delay)
}

export function connectChat() {
  manualClosed = false
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return
  if (!localStorage.getItem('token')) return // 未登录不连接
  try { ws?.close() } catch { /* ignored */ }
  ws = new WebSocket(wsUrl())

  ws.onopen = () => {
    connected.value = true
    reconnectAttempts = 0
    startHeartbeat()
    emit('status', true)
  }
  ws.onmessage = (ev) => {
    let data
    try { data = JSON.parse(ev.data) } catch { return }
    if (data.type === 'pong') return
    emit(data.type, data)
    emit('any', data)
  }
  ws.onclose = () => {
    connected.value = false
    stopHeartbeat()
    emit('status', false)
    scheduleReconnect()
  }
  ws.onerror = () => { try { ws?.close() } catch { /* ignored */ } }
}

export function disconnectChat() {
  manualClosed = true
  stopHeartbeat()
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
  try { ws?.close() } catch { /* ignored */ }
  ws = null
  connected.value = false
}

/** 发送消息；连接未就绪时返回 false（调用方应先 connect）。 */
export function sendChat(payload) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(payload))
    return true
  }
  connectChat()
  return false
}

export function useChat() {
  return { connected, connect: connectChat, disconnect: disconnectChat, send: sendChat, on: onChat, off: offChat }
}
