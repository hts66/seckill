import request from '../utils/request'

/** 获取用户订单 */
export function getOrders() {
  return request.get('/orders')
}

/** 获取单个订单 */
export function getOrder(orderNo) {
  return request.get(`/orders/${orderNo}`)
}

/** 零钱支付：扣减余额并把订单标记为已支付 */
export function payOrder(orderNo) {
  return request.post(`/orders/pay/${orderNo}`)
}

/** 确认收货 */
export function confirmReceipt(orderNo) {
  return request.post(`/orders/${orderNo}/confirm`)
}

/** 我的零钱余额（首次查询自动开户赠金） */
export const getWallet = () => request.get('/wallet')

/** 自助设置零钱余额（演示） */
export const setWalletBalance = amount => request.put('/wallet/balance', { amount })

/** 取消订单（已支付未发货时零钱原路退回） */
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
