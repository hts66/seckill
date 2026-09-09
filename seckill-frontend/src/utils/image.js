// 图片工具函数：统一处理 MinIO 图片 URL 与多图解析

/**
 * 把图片 URL 里的 localhost / 127.0.0.1 替换成当前页面的 hostname。
 *
 * 图片由 MinIO 返回，存储时用的是 MINIO_PUBLIC_URL=http://localhost:9010，
 * 电脑浏览器访问没问题，但手机通过局域网 IP 访问前端时，localhost 指向手机自身，
 * 导致图片加载不出来。这里按访问端动态替换 host，让电脑/手机都能正常看图。
 */
export function resolveImageUrl(url) {
  if (!url) return ''
  try {
    const u = new URL(url)
    if (u.hostname === 'localhost' || u.hostname === '127.0.0.1') {
      u.hostname = window.location.hostname || 'localhost'
    }
    return u.toString()
  } catch {
    return url
  }
}

/**
 * 解析秒杀项 product_images（JSON 数组字符串），返回 URL 数组。
 * 兼容空值、非数组、非法 JSON。
 */
export function parseImageList(imagesJson) {
  if (!imagesJson) return []
  try {
    const arr = JSON.parse(imagesJson)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

/** 取第一张图（已做 host 替换），无图返回空字符串。 */
export function firstImage(imagesJson) {
  const list = parseImageList(imagesJson)
  return list.length > 0 ? resolveImageUrl(list[0]) : ''
}
