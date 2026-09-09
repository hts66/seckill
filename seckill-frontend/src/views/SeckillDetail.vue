<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '../stores/user'
import { getSeckillItemDetail } from '../api/seckill'
import { resolveImageUrl, parseImageList } from '../utils/image'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const itemId = Number(route.params.itemId)
const item = ref(null)
const loading = ref(true)
const error = ref('')
const images = ref([])          // 已解析的原始 URL 数组
const activeIndex = ref(0)      // 当前选中图片下标
const now = ref(Date.now())
let timer = null

const activeImage = computed(() =>
  images.value.length > 0 ? resolveImageUrl(images.value[activeIndex.value]) : ''
)

async function loadDetail() {
  loading.value = true
  error.value = ''
  try {
    const res = await getSeckillItemDetail(itemId)
    item.value = res.data || null
    images.value = parseImageList(item.value?.productImages)
    activeIndex.value = 0
  } catch (e) {
    error.value = e?.message || '商品加载失败'
  } finally {
    loading.value = false
  }
}

function selectImage(idx) {
  activeIndex.value = idx
}

function prevImage() {
  if (images.value.length <= 1) return
  activeIndex.value = (activeIndex.value - 1 + images.value.length) % images.value.length
}

function nextImage() {
  if (images.value.length <= 1) return
  activeIndex.value = (activeIndex.value + 1) % images.value.length
}

const status = computed(() => {
  if (!item.value) return 'loading'
  const start = new Date(item.value.activityStartTime).getTime()
  const end = new Date(item.value.activityEndTime).getTime()
  if (now.value < start) return 'upcoming'
  if (now.value > end) return 'ended'
  return 'active'
})

const statusText = computed(() => ({
  active: '进行中',
  upcoming: '即将开抢',
  ended: '已结束'
}[status.value] || ''))

// 倒计时目标：进行中显示距结束，预热中显示距开始
const countdownTarget = computed(() => {
  if (!item.value) return null
  return status.value === 'active'
    ? item.value.activityEndTime
    : item.value.activityStartTime
})

const countdownText = computed(() => {
  if (!countdownTarget.value) return ''
  const t = new Date(countdownTarget.value).getTime() - now.value
  if (t <= 0) return '00:00:00'
  const h = Math.floor(t / 3600000)
  const m = Math.floor((t % 3600000) / 60000)
  const s = Math.floor((t % 60000) / 1000)
  const pad = n => String(n).padStart(2, '0')
  return `${pad(h)}:${pad(m)}:${pad(s)}`
})

const countdownLabel = computed(() =>
  status.value === 'active' ? '距结束' : '距开抢'
)

function formatPrice(p) { return Number(p || 0).toFixed(2) }

function calcDiscount(original, seckill) {
  if (!original || original <= 0) return 0
  return Math.round((1 - seckill / original) * 100)
}

function formatTime(t) {
  if (!t) return '-'
  return String(t).replace('T', ' ').slice(0, 16)
}

function goSeckill() {
  if (userStore.isAdmin) return
  if (!userStore.isLoggedIn) {
    router.push({ path: '/login', query: { redirect: `/seckill/${itemId}` } })
    return
  }
  router.push(`/seckill/${itemId}`)
}

onMounted(() => {
  loadDetail()
  timer = setInterval(() => { now.value = Date.now() }, 1000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <div class="container fade-in">
    <!-- 加载中 -->
    <div v-if="loading" style="text-align:center;padding:80px 0;">
      <p class="pulse" style="font-size:18px;color:var(--text-light);">加载商品详情中...</p>
    </div>

    <!-- 错误 -->
    <div v-else-if="error" class="empty-state">
      <div class="icon">⚠️</div>
      <p>{{ error }}</p>
      <router-link to="/" class="btn btn-outline" style="margin-top:16px;display:inline-flex;">返回首页</router-link>
    </div>

    <!-- 详情 -->
    <div v-else-if="item" class="detail-wrap">
      <!-- 左侧图片区 -->
      <div class="gallery">
        <div class="main-img">
          <img v-if="activeImage" :src="activeImage" alt="商品主图" />
          <div v-else class="main-img placeholder">
            {{ item.productName?.charAt(0) || '?' }}
          </div>

          <!-- 左右切换箭头 -->
          <button
            v-if="images.length > 1"
            class="img-arrow left"
            @click="prevImage"
            aria-label="上一张"
          >‹</button>
          <button
            v-if="images.length > 1"
            class="img-arrow right"
            @click="nextImage"
            aria-label="下一张"
          >›</button>

          <!-- 图片计数 -->
          <span v-if="images.length > 1" class="img-counter">{{ activeIndex + 1 }} / {{ images.length }}</span>
        </div>

        <!-- 缩略图 -->
        <div v-if="images.length > 1" class="thumbs">
          <div
            v-for="(img, idx) in images"
            :key="idx"
            class="thumb"
            :class="{ active: activeIndex === idx }"
            @click="selectImage(idx)"
          >
            <img :src="resolveImageUrl(img)" alt="缩略图" />
          </div>
        </div>
      </div>

      <!-- 右侧信息区 -->
      <div class="info">
        <div class="status-row">
          <span class="badge" :class="status === 'active' ? 'badge-red' : status === 'upcoming' ? 'badge-yellow' : 'badge-gray'">
            {{ statusText }}
          </span>
          <span v-if="item.activityName" class="activity-name">{{ item.activityName }}</span>
        </div>

        <h1 class="name">{{ item.productName }}</h1>
        <p v-if="item.productTitle" class="title">{{ item.productTitle }}</p>
        <p v-if="item.activityDescription" class="desc">{{ item.activityDescription }}</p>

        <!-- 价格区 -->
        <div class="price-box">
          <div class="price-row">
            <span class="label">秒杀价</span>
            <span class="seckill-price">￥{{ formatPrice(item.seckillPrice) }}</span>
            <span class="original-price">￥{{ formatPrice(item.originalPrice) }}</span>
            <span class="badge badge-red">{{ calcDiscount(item.originalPrice, item.seckillPrice) }}% OFF</span>
          </div>
          <div class="meta-grid">
            <div class="meta-item"><span class="label">库存</span><span>{{ item.stock }} 件</span></div>
            <div class="meta-item"><span class="label">限购</span><span>{{ item.limitPerUser }} 件/人</span></div>
            <div class="meta-item"><span class="label">开始</span><span>{{ formatTime(item.activityStartTime) }}</span></div>
            <div class="meta-item"><span class="label">结束</span><span>{{ formatTime(item.activityEndTime) }}</span></div>
          </div>
        </div>

        <!-- 倒计时 + 操作 -->
        <div class="action-box">
          <div class="countdown-row">
            <span class="label">{{ countdownLabel }}</span>
            <span class="countdown">{{ countdownText }}</span>
          </div>

          <button
            v-if="userStore.isAdmin"
            class="btn btn-outline btn-lg"
            disabled
          >管理员无购买权限</button>
          <button
            v-else-if="status === 'active'"
            class="btn btn-primary btn-lg"
            @click="goSeckill"
          >立即抢购</button>
          <button
            v-else-if="status === 'upcoming'"
            class="btn btn-outline btn-lg"
            disabled
          >即将开抢</button>
          <button
            v-else
            class="btn btn-outline btn-lg"
            disabled
          >活动已结束</button>

          <router-link to="/" class="back-link">← 返回活动列表</router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.detail-wrap {
  display: flex;
  gap: 28px;
  margin-top: 28px;
  flex-wrap: wrap;
}
.gallery { flex: 1; min-width: 320px; }
.main-img {
  position: relative;
  width: 100%;
  aspect-ratio: 1 / 1;
  border-radius: var(--radius);
  overflow: hidden;
  background: var(--bg);
  border: 1px solid var(--border);
}
.main-img img { width: 100%; height: 100%; object-fit: cover; display: block; }
.img-arrow {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  width: 40px; height: 40px;
  border: none; border-radius: 50%;
  background: rgba(0, 0, 0, 0.35);
  color: #fff;
  font-size: 26px;
  line-height: 1;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: background 0.2s;
}
.img-arrow:hover { background: rgba(0, 0, 0, 0.6); }
.img-arrow.left { left: 10px; }
.img-arrow.right { right: 10px; }
.img-counter {
  position: absolute;
  right: 12px; bottom: 12px;
  padding: 3px 10px;
  border-radius: 12px;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  font-size: 12px;
  font-family: monospace;
}
.main-img.placeholder {
  display: flex; align-items: center; justify-content: center;
  font-size: 96px; color: #fff;
  background: linear-gradient(135deg, #667eea, #764ba2);
}
.thumbs { display: flex; gap: 10px; margin-top: 12px; flex-wrap: wrap; }
.thumb {
  width: 64px; height: 64px; border-radius: 8px; overflow: hidden;
  border: 2px solid transparent; cursor: pointer; background: var(--bg);
}
.thumb.active { border-color: var(--primary); }
.thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }

.info { flex: 1; min-width: 320px; }
.status-row { display: flex; align-items: center; gap: 10px; }
.activity-name { font-size: 13px; color: var(--text-light); }
.name { font-size: 26px; font-weight: 700; margin: 12px 0 6px; }
.title { font-size: 15px; color: var(--primary); margin: 0 0 10px; }
.desc { font-size: 14px; color: var(--text-light); line-height: 1.7; margin: 0 0 16px; }

.price-box {
  background: var(--card-bg); border: 1px solid var(--border);
  border-radius: var(--radius); padding: 18px;
}
.price-row { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
.label { font-size: 13px; color: var(--text-light); }
.seckill-price { font-size: 34px; font-weight: 800; color: var(--warning); }
.original-price { font-size: 15px; color: var(--text-light); text-decoration: line-through; }
.meta-grid {
  display: grid; grid-template-columns: 1fr 1fr;
  gap: 10px 20px; margin-top: 16px;
  border-top: 1px dashed var(--border); padding-top: 14px;
}
.meta-item { display: flex; justify-content: space-between; font-size: 13px; }

.action-box { margin-top: 20px; }
.countdown-row {
  display: flex; align-items: center; gap: 10px; margin-bottom: 14px;
}
.countdown {
  font-size: 26px; font-weight: 800; color: var(--primary);
  font-family: monospace;
}
.btn-lg { width: 100%; padding: 14px; font-size: 17px; }
.back-link {
  display: inline-block; margin-top: 14px;
  font-size: 13px; color: var(--text-light); text-decoration: none;
}
.back-link:hover { color: var(--primary); }

@media (max-width: 760px) {
  .detail-wrap { flex-direction: column; }
  .meta-grid { grid-template-columns: 1fr; }
}
</style>
