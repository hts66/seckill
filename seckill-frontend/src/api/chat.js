import request from '../utils/request'

// ---------------- 用户端 ----------------

/** 拉取某订单的聊天历史（会话不存在时返回 { conversationId:null, messages:[] }） */
export function getOrderChat(orderNo) {
  return request.get(`/chat/orders/${orderNo}/messages`)
}

/** 进入聊天窗口，清零用户未读 */
export function readOrderChat(orderNo) {
  return request.post(`/chat/orders/${orderNo}/read`)
}

// ---------------- 客服端（管理员） ----------------

export function getAdminConversations() {
  return request.get('/admin/chat/conversations')
}

export function getAdminMessages(conversationId) {
  return request.get(`/admin/chat/conversations/${conversationId}/messages`)
}

export function readAdminConversation(conversationId) {
  return request.post(`/admin/chat/conversations/${conversationId}/read`)
}
