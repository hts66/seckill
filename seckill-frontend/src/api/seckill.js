import request from '../utils/request'

/** 获取秒杀商品列表（进行中） */
export function getSeckillItems() {
  return request.get('/seckill/items')
}

/** 获取预热中商品列表（即将开抢） */
export function getUpcomingItems() {
  return request.get('/seckill/upcoming')
}

/** 获取单个商品详情 */
export function getSeckillItemDetail(itemId) {
  return request.get(`/seckill/items/${itemId}`)
}

/** 获取动态秒杀路径 */
export function getSeckillPath(itemId) {
  return request.get(`/seckill/path/${itemId}`)
}

/** 执行秒杀 */
export function executeSeckill(pathKey, itemId) {
  return request.post(`/seckill/execute/${pathKey}`, { itemId })
}

/** 查询秒杀结果 */
export function getSeckillResult(itemId) {
  return request.get(`/seckill/result/${itemId}`)
}

/** 活动预热（管理）- 加载库存到Redis */
export function warmUpActivity(activityId) {
  return request.post(`/seckill/admin/warmup/${activityId}`)
}

/** ========== 管理接口 ========== */

/** 获取所有活动 */
export function getActivities() {
  return request.get('/admin/activities')
}

/** 创建活动 */
export function createActivity(data) {
  return request.post('/admin/activities', data)
}

/** 更新活动 */
export function updateActivity(id, data) {
  return request.put(`/admin/activities/${id}`, data)
}

/** 删除活动 */
export function deleteActivity(id) {
  return request.delete(`/admin/activities/${id}`)
}

/** 获取活动下的秒杀项 */
export function getActivityItems(activityId) {
  return request.get(`/admin/items/${activityId}`)
}

/** 创建秒杀项 */
export function createSeckillItem(data) {
  return request.post('/admin/items', data)
}

/** 更新秒杀项 */
export function updateSeckillItem(id, data) {
  return request.put(`/admin/items/${id}`, data)
}

/** 删除秒杀项 */
export function deleteSeckillItem(id) {
  return request.delete(`/admin/items/${id}`)
}

/** 查询 Redis 库存 */
export function getRedisStock(itemId) {
  return request.get(`/admin/stock/${itemId}`)
}

/** 获取所有订单（管理端，支持筛选与分页） */
export function getAllOrders(params = {}) {
  return request.get('/admin/orders', { params })
}

/** 获取订单详情（管理端） */
export function getAdminOrderDetail(orderNo) {
  return request.get(`/admin/orders/${orderNo}`)
}

/** 管理员代取消/退款 */
export function cancelAdminOrder(orderNo) {
  return request.post(`/admin/orders/${orderNo}/cancel`)
}

export function shipOrder(orderNo) {
  return request.post(`/admin/orders/${orderNo}/ship`)
}
