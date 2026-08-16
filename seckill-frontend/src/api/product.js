import request from '../utils/request'

/** 获取所有商品 */
export function getProducts() {
  return request.get('/admin/products')
}

/** 获取单个商品 */
export function getProduct(id) {
  return request.get(`/admin/products/${id}`)
}

/** 创建商品 */
export function createProduct(data) {
  return request.post('/admin/products', data)
}

/** 更新商品 */
export function updateProduct(id, data) {
  return request.put(`/admin/products/${id}`, data)
}

/** 删除商品 */
export function deleteProduct(id) {
  return request.delete(`/admin/products/${id}`)
}

/** 上传单张图片 */
export function uploadImage(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/admin/products/upload', formData)
}

/** 批量上传图片 */
export function uploadImages(files) {
  const formData = new FormData()
  files.forEach(f => formData.append('files', f))
  return request.post('/admin/products/upload/batch', formData)
}

/** 删除已上传的图片（MinIO + 前端联动） */
export function deleteUploadedImage(url) {
  return request.delete('/admin/products/upload', { data: { url } })
}
