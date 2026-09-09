import request from '../utils/request'

/** 获取用户订单 */
export function getOrders() {
  return request.get('/orders')
}

/** 获取单个订单 */
export function getOrder(orderNo) {
  return request.get(`/orders/${orderNo}`)
}

/** 模拟支付：点击支付按钮直接把订单标记为已支付 */
export function payOrder(orderNo) {
  return request.post(`/orders/pay/${orderNo}`)
}

/** 取消订单（已支付时按模拟退款处理） */
export function cancelOrder(orderNo) {
  return request.post(`/orders/cancel/${orderNo}`)
}

export function bindOrderAddress(orderNo, addressId) {
  return request.put(`/orders/${orderNo}/address`, { addressId })
}

export const getAddresses = () => request.get('/addresses')
export const createAddress = data => request.post('/addresses', data)
export const updateAddress = (id, data) => request.put(`/addresses/${id}`, data)
export const setDefaultAddress = id => request.put(`/addresses/${id}/default`)
export const deleteAddress = id => request.delete(`/addresses/${id}`)
