import request from '../utils/request'

// ---------------- 用户端 ----------------

/** 拉取某订单的聊天历史（游标分页；会话不存在时返回 { conversationId:null, messages:[] }） */
export function getOrderChat(orderNo, beforeId, size = 30) {
  return request.get(`/chat/orders/${orderNo}/messages`, {
    params: { beforeId: beforeId || undefined, size },
  })
}

/** 进入聊天窗口，清零用户未读 */
export function readOrderChat(orderNo) {
  return request.post(`/chat/orders/${orderNo}/read`)
}

/** 用户各订单的未读消息数（orderNo -> count） */
export function getUnreadCounts() {
  return request.get('/chat/unread-counts')
}

// ---------------- 客服端（管理员） ----------------

export function getAdminConversations() {
  return request.get('/admin/chat/conversations')
}

export function getAdminMessages(conversationId, beforeId, size = 30) {
  return request.get(`/admin/chat/conversations/${conversationId}/messages`, {
    params: { beforeId: beforeId || undefined, size },
  })
}

export function readAdminConversation(conversationId) {
  return request.post(`/admin/chat/conversations/${conversationId}/read`)
}
