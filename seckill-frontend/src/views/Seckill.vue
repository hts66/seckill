<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { getSeckillPath, executeSeckill, getSeckillResult } from '../api/seckill'

const route = useRoute()
const itemId = Number(route.params.itemId)
const stage = ref('ready')
const pathKey = ref('')
const result = ref(null)
const errorMsg = ref('')
const countdown = ref(3)
let pollTimer = null
let countdownTimer = null

async function loadPath() {
  try {
    const res = await getSeckillPath(itemId)
    pathKey.value = res.data?.path || res.data?.pathKey || ''
    if (!pathKey.value) throw new Error('服务器未返回秒杀路径')
    return true
  } catch (e) {
    errorMsg.value = e.message || '获取秒杀路径失败'
    return false
  }
}

async function doSeckill() {
  if (stage.value === 'executing' || stage.value === 'queue') return
  stage.value = 'executing'
  errorMsg.value = ''
  try {
    if (!pathKey.value && !(await loadPath())) {
      stage.value = 'ready'
      return
    }
    const res = await executeSeckill(pathKey.value, itemId)
    pathKey.value = ''
    const r = res.data
    if (r.status === 0) {
      stage.value = 'queue'
      startPolling()
    } else if (r.status === 1) {
      stage.value = 'success'
      result.value = r
    } else {
      const map = { 2: 'soldout', 3: 'bought', 5: 'ended' }
      stage.value = map[r.status] || 'soldout'
      if (r.status === 4) {
        stage.value = 'ready'
        errorMsg.value = r.message || '订单创建失败，请重试'
      }
      if (r.status === 5) errorMsg.value = '活动未开始或已结束'
    }
  } catch (e) {
    pathKey.value = ''
    errorMsg.value = e.message || '秒杀失败，请重试'
    stage.value = 'ready'
  }
}

function startPolling() {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = setInterval(async () => {
    try {
      const r = (await getSeckillResult(itemId)).data
      if (r.status === 1 || r.orderNo) {
        stage.value = 'success'
        result.value = r
        clearInterval(pollTimer)
      } else if (r.status === 2) {
        stage.value = 'soldout'
        clearInterval(pollTimer)
      } else if (r.status === 3) {
        stage.value = 'bought'
        clearInterval(pollTimer)
      } else if (r.status === 4) {
        stage.value = 'ready'
        errorMsg.value = r.message || '订单创建失败，请重试'
        clearInterval(pollTimer)
      }
    } catch (e) { /* keep polling during transient network errors */ }
  }, 1000)
}

onMounted(() => {
  loadPath()
  countdownTimer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) {
      clearInterval(countdownTimer)
      doSeckill()
    }
  }, 1000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
  if (countdownTimer) clearInterval(countdownTimer)
})
</script>

<template>
  <div class="container fade-in">
    <div class="card seckill-card">
      <div v-if="stage === 'ready'">
        <div class="seckill-mark">⚡</div>
        <h1>秒杀倒计时</h1>
        <div class="countdown">{{ countdown > 0 ? countdown : 0 }}</div>
        <p class="muted">秒后自动开抢</p>
        <p class="flow-note">抢购成功后再填写收货地址，不会耽误抢购。</p>
        <div v-if="errorMsg" class="error-message">{{ errorMsg }}</div>
        <button class="btn btn-primary" style="margin-top:16px;" @click="doSeckill">立即抢购</button>
      </div>

      <div v-else-if="stage === 'executing' || stage === 'queue'" class="result-panel">
        <div class="pulse seckill-mark">{{ stage === 'executing' ? '⏳' : '↻' }}</div>
        <p>{{ stage === 'executing' ? '正在秒杀...' : '排队中，正在生成订单...' }}</p>
      </div>

      <div v-else-if="stage === 'success'" class="result-panel success-panel">
        <div class="seckill-mark">✓</div>
        <h1>恭喜抢到</h1>
        <p class="muted">订单号：{{ result?.orderNo }}</p>
        <p class="address-hint">请前往订单页支付并填写收货地址，商家确认地址后发货。</p>
        <router-link to="/orders" class="btn btn-primary" style="margin-top:16px;display:inline-flex;">去处理订单</router-link>
      </div>

      <div v-else-if="stage === 'soldout'" class="result-panel">
        <div class="seckill-mark muted">×</div><h1>已抢完</h1>
        <p class="muted">手慢了一步，下次早点来。</p>
        <router-link to="/" class="btn btn-outline" style="margin-top:16px;display:inline-flex;">返回首页</router-link>
      </div>

      <div v-else-if="stage === 'bought'" class="result-panel">
        <div class="seckill-mark">✓</div><h1>已经买过</h1>
        <p class="muted">每人限购一件，请前往订单页查看。</p>
        <router-link to="/orders" class="btn btn-primary" style="margin-top:16px;display:inline-flex;">查看订单</router-link>
      </div>

      <div v-else class="result-panel">
        <div class="seckill-mark muted">○</div><h1>活动已结束</h1>
        <p class="muted">这场活动已经结束。</p>
        <router-link to="/" class="btn btn-outline" style="margin-top:16px;display:inline-flex;">返回首页</router-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.seckill-card{max-width:620px;margin:60px auto 0;padding:48px 40px;text-align:center}
.seckill-mark{font-size:64px;line-height:1;margin-bottom:16px;color:var(--primary)}
.countdown{font-size:56px;font-weight:800;color:var(--primary);margin:20px 0}
.muted{color:var(--text-light)}
.flow-note{display:inline-block;margin:18px auto 0;padding:9px 12px;background:var(--primary-light);color:var(--text);font-size:13px;border-radius:6px}
.error-message{color:var(--danger);margin-top:12px;font-size:14px}
.result-panel{padding:30px 0}.result-panel h1{margin:12px 0}.success-panel .seckill-mark{color:var(--success)}
.address-hint{max-width:380px;margin:14px auto 0;color:var(--text-light);font-size:13px;line-height:1.6}
@media (max-width:600px){.seckill-card{margin-top:24px;padding:36px 20px}.countdown{font-size:48px}}
</style>
