<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { getSeckillItems, getUpcomingItems } from '../api/seckill'

const router = useRouter()
const userStore = useUserStore()
const activeItems = ref([])     // 进行中的秒杀
const upcomingItems = ref([])   // 预热中的秒杀
const loading = ref(true)
const error = ref('')

// 倒计时用
const now = ref(Date.now())
let timer = null
let refreshInFlight = false

async function loadItems({ silent = false } = {}) {
  if (refreshInFlight) return
  refreshInFlight = true
  if (!silent) loading.value = true
  error.value = ''
  try {
    // 同时加载进行中和预热中的商品
    const [activeRes, upcomingRes] = await Promise.allSettled([
      getSeckillItems(),
      getUpcomingItems()
    ])

    if (activeRes.status === 'fulfilled') {
      activeItems.value = activeRes.value.data || []
    }
    if (upcomingRes.status === 'fulfilled') {
      upcomingItems.value = upcomingRes.value.data || []
    }

    if (activeItems.value.length === 0 && upcomingItems.value.length === 0) {
      error.value = '暂无秒杀活动'
    }

  } catch (e) {
    error.value = '暂无秒杀活动'
  } finally {
    if (!silent) loading.value = false
    refreshInFlight = false
  }
}

function startStatusTimer() {
  if (timer) return
  timer = setInterval(() => {
    now.value = Date.now()

    // 到达开始或结束时间后重新查询后端，让商品及时在预热、进行中和结束状态间切换。
    const reachedStart = upcomingItems.value.some(item =>
      new Date(item.activityStartTime).getTime() <= now.value
    )
    const reachedEnd = activeItems.value.some(item =>
      new Date(item.activityEndTime).getTime() <= now.value
    )
    if (reachedStart || reachedEnd) loadItems({ silent: true })
  }, 1000)
}

function goSeckill(item) {
  if (!userStore.isLoggedIn) { router.push('/login'); return }
  router.push(`/seckill/${item.id}`)
}

function calcDiscount(original, seckill) {
  if (!original || original <= 0) return 0
  return Math.round((1 - seckill / original) * 100)
}

function formatPrice(p) { return Number(p || 0).toFixed(2) }

// 格式化倒计时
function formatCountdown(targetTime) {
  if (!targetTime) return ''
  const t = new Date(targetTime).getTime() - now.value
  if (t <= 0) return '即将开始'
  const h = Math.floor(t / 3600000)
  const m = Math.floor((t % 3600000) / 60000)
  const s = Math.floor((t % 60000) / 1000)
  if (h > 0) return `${h}时${m}分${s}秒`
  if (m > 0) return `${m}分${s}秒`
  return `${s}秒`
}

// 获取第一张图片或placeholder
function getFirstImage(imagesJson) {
  if (!imagesJson) return null
  try {
    const arr = JSON.parse(imagesJson)
    return arr.length > 0 ? arr[0] : null
  } catch {
    return null
  }
}

onMounted(() => {
  loadItems()
  startStatusTimer()
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <div class="container fade-in">
    <div v-if="loading" style="text-align:center;padding:80px 0;">
      <p class="pulse" style="font-size:18px;color:var(--text-light);">加载秒杀商品中...</p>
    </div>

    <div v-else-if="error && activeItems.length === 0 && upcomingItems.length === 0" class="empty-state">
      <div class="icon">🕐</div>
      <p>{{ error }}</p>
    </div>

    <div v-else>
      <!-- ==================== 预热中的商品（即将开抢） ==================== -->
      <div v-if="upcomingItems.length > 0" style="margin-top:20px;">
        <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px;">
          <h1 class="page-title" style="margin:0;">🔥 即将开抢</h1>
          <span class="badge badge-yellow pulse">预热中</span>
        </div>

        <div v-for="item in upcomingItems" :key="item.id" class="card fade-in upcoming-card"
             style="margin-bottom:14px;overflow:hidden;">
          <div style="display:flex;cursor:default;">
            <!-- 商品图片 -->
            <div class="item-image" style="width:120px;height:120px;flex-shrink:0;">
              <img
                v-if="getFirstImage(item.productImages)"
                :src="getFirstImage(item.productImages)"
                style="width:100%;height:100%;object-fit:cover;"
                alt="商品图片"
              />
              <div v-else style="width:100%;height:100%;display:flex;align-items:center;justify-content:center;font-size:40px;background:linear-gradient(135deg,#667eea,#764ba2);color:#fff;">
                {{ item.productName?.charAt(0) || '?' }}
              </div>
            </div>

            <div style="padding:14px 18px;flex:1;display:flex;flex-direction:column;justify-content:space-between;">
              <div>
                <div style="display:flex;align-items:center;gap:8px;">
                  <span style="font-size:16px;font-weight:600;">{{ item.productName }}</span>
                  <span v-if="item.productTitle" style="font-size:12px;color:var(--primary);background:var(--primary-light);padding:1px 8px;border-radius:4px;">
                    {{ item.productTitle }}
                  </span>
                </div>
                <div style="font-size:12px;color:var(--text-light);margin-top:4px;">{{ item.activityName }}</div>
              </div>

              <div>
                <div style="display:flex;align-items:center;gap:8px;margin-bottom:4px;">
                  <span style="font-size:22px;font-weight:800;color:var(--warning);">￥{{ formatPrice(item.seckillPrice) }}</span>
                  <span style="font-size:13px;color:var(--text-light);text-decoration:line-through;">
                    ￥{{ formatPrice(item.originalPrice) }}
                  </span>
                  <span class="badge badge-red">{{ calcDiscount(item.originalPrice, item.seckillPrice) }}% OFF</span>
                </div>

                <!-- 倒计时 -->
                <div style="display:flex;align-items:center;gap:6px;margin-top:6px;">
                  <span style="font-size:12px;color:var(--text-light);">距开抢：</span>
                  <span style="font-size:18px;font-weight:800;color:var(--primary);font-family:monospace;">
                    {{ formatCountdown(item.activityStartTime) }}
                  </span>
                  <button class="btn btn-primary btn-sm" disabled style="opacity:0.5;">即将开抢</button>
                </div>

                <div style="font-size:11px;color:var(--text-light);margin-top:2px;">
                  库存 {{ item.stock }} 件 | 每人限购 {{ item.limitPerUser }} 件
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- ==================== 进行中的秒杀 ==================== -->
      <div v-if="activeItems.length > 0" style="margin-top:20px;">
        <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:16px;">
          <h1 class="page-title" style="margin:0;">⚡ 限时秒杀</h1>
          <span class="badge badge-red pulse">进行中</span>
        </div>

        <div v-for="item in activeItems" :key="item.id" class="card fade-in"
             style="display:flex;margin-bottom:14px;cursor:pointer;overflow:hidden;"
             @click="goSeckill(item)">
          <!-- 商品图片 -->
          <div style="width:120px;height:120px;flex-shrink:0;">
            <img
              v-if="getFirstImage(item.productImages)"
              :src="getFirstImage(item.productImages)"
              style="width:100%;height:100%;object-fit:cover;"
              alt="商品图片"
            />
            <div v-else style="width:100%;height:100%;display:flex;align-items:center;justify-content:center;font-size:40px;background:linear-gradient(135deg,#667eea,#764ba2);color:#fff;">
              {{ item.productName?.charAt(0) || '?' }}
            </div>
          </div>

          <div style="padding:14px 18px;flex:1;display:flex;flex-direction:column;justify-content:space-between;">
            <div>
              <div style="display:flex;align-items:center;gap:8px;">
                <span style="font-size:16px;font-weight:600;">{{ item.productName }}</span>
                <span v-if="item.productTitle" style="font-size:12px;color:var(--primary);background:var(--primary-light);padding:1px 8px;border-radius:4px;">
                  {{ item.productTitle }}
                </span>
              </div>
              <div style="font-size:12px;color:var(--text-light);margin-top:4px;">{{ item.activityName }}</div>
            </div>
            <div style="display:flex;align-items:center;justify-content:space-between;">
              <div>
                <span style="font-size:24px;font-weight:800;color:var(--primary);">￥{{ formatPrice(item.seckillPrice) }}</span>
                <span style="font-size:13px;color:var(--text-light);text-decoration:line-through;margin-left:8px;">
                  ￥{{ formatPrice(item.originalPrice) }}
                </span>
                <span class="badge badge-red" style="margin-left:8px;">{{ calcDiscount(item.originalPrice, item.seckillPrice) }}% OFF</span>
              </div>
              <button class="btn btn-primary btn-sm">立即抢购</button>
            </div>
            <div style="font-size:12px;color:var(--text-light);">库存：{{ item.stock }} 件 | 每人限购 {{ item.limitPerUser }} 件</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.upcoming-card {
  position: relative;
}
.upcoming-card::before {
  content: '';
  position: absolute;
  top: 0; left: -100%;
  width: 100%; height: 3px;
  background: linear-gradient(90deg, transparent, var(--warning), transparent);
  animation: shimmer 2s infinite;
}
@keyframes shimmer {
  0% { left: -100%; }
  100% { left: 100%; }
}
</style>
