<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { getOrders, payOrder, cancelOrder, getAddresses, bindOrderAddress } from '../api/order'

const router = useRouter()
const userStore = useUserStore()
const orders = ref([])
const addresses = ref([])
const loading = ref(true)
const error = ref('')
const addressError = ref('')
const editingOrderNo = ref(null)
const selectedAddressId = ref(null)
let refreshTimer = null
let refreshAttempts = 0

const paymentStatus = ['待支付', '已支付', '已取消', '超时取消']
const fulfillmentStatus = ['待填写地址', '待发货', '已发货', '已收货']
const missingAddressOrders = computed(() => orders.value.filter(o => !o.receiverName && o.status < 2))

async function loadOrders({ silent = false } = {}) {
  if (!userStore.isLoggedIn) { router.push('/login'); return }
  if (!silent) loading.value = true
  error.value = ''
  try {
    const res = await getOrders()
    orders.value = res.data || []
    if (orders.value.length > 0 && refreshTimer) {
      clearInterval(refreshTimer)
      refreshTimer = null
    }
  } catch (e) { error.value = e.message || '订单加载失败' }
  finally { if (!silent) loading.value = false }
}

async function loadAddresses() {
  try { addresses.value = (await getAddresses()).data || [] }
  catch (e) { addressError.value = e.message || '地址加载失败' }
}

function startAddressEdit(orderNo) {
  editingOrderNo.value = orderNo
  selectedAddressId.value = addresses.value.find(a => a.isDefault)?.id || addresses.value[0]?.id || null
  addressError.value = ''
}

function stopAddressEdit() { editingOrderNo.value = null; addressError.value = '' }

async function saveAddress() {
  if (!editingOrderNo.value || !selectedAddressId.value) {
    addressError.value = '请选择一个收货地址'
    return
  }
  try {
    await bindOrderAddress(editingOrderNo.value, selectedAddressId.value)
    stopAddressEdit()
    await loadOrders()
  } catch (e) { addressError.value = e?.response?.data?.message || e.message || '地址保存失败' }
}

async function doPay(orderNo) {
  try { await payOrder(orderNo); await loadOrders() }
  catch (e) { alert(e.message) }
}

async function doCancel(orderNo) {
  if (!confirm('确定取消这个订单吗？')) return
  try { await cancelOrder(orderNo); await loadOrders() }
  catch (e) { alert(e.message) }
}

function startShortPolling() {
  refreshTimer = setInterval(async () => {
    refreshAttempts++
    await loadOrders({ silent: true })
    if (orders.value.length > 0 || refreshAttempts >= 10) {
      clearInterval(refreshTimer)
      refreshTimer = null
    }
  }, 1000)
}

onMounted(async () => {
  await Promise.all([loadOrders(), loadAddresses()])
  if (orders.value.length === 0) startShortPolling()
})
onUnmounted(() => { if (refreshTimer) clearInterval(refreshTimer) })
</script>

<template>
  <div class="container fade-in">
    <h1 class="page-title" style="margin-top:20px;">我的订单</h1>
    <div v-if="error" class="error-message">{{ error }}</div>

    <div v-if="missingAddressOrders.length" class="address-alert">
      <strong>有 {{ missingAddressOrders.length }} 个订单还没有收货地址</strong>
      <span>填写后商家才能安排发货。</span>
    </div>

    <div v-if="loading" class="empty-state"><p class="pulse">加载中...</p></div>
    <div v-else-if="orders.length === 0" class="empty-state">
      <div class="icon">□</div><p>暂无订单</p>
      <router-link to="/" class="btn btn-primary" style="margin-top:16px;display:inline-flex;">去抢购</router-link>
    </div>

    <div v-else>
      <div v-for="o in orders" :key="o.id" class="card order-card">
        <div class="order-head">
          <span class="product-name">{{ o.productName || '秒杀商品' }}</span>
          <span class="badge" :class="o.status === 1 ? 'badge-green' : o.status === 0 ? 'badge-yellow' : 'badge-gray'">
            {{ paymentStatus[o.status] || '未知' }}
          </span>
        </div>
        <div class="order-no">订单号：{{ o.orderNo }}</div>

        <div v-if="o.receiverName" class="shipping-line">
          <strong>收货信息：</strong>{{ o.receiverName }} {{ o.receiverPhone }} ·
          {{ o.receiverProvince }} {{ o.receiverCity }} {{ o.receiverDistrict }} {{ o.receiverDetail }}
        </div>
        <div v-else class="shipping-line missing">
          <span>待填写收货地址，填写后商家才能发货</span>
          <button class="btn btn-outline btn-sm" @click="startAddressEdit(o.orderNo)">填写地址</button>
        </div>

        <div class="order-foot">
          <span class="amount">￥{{ Number(o.amount || 0).toFixed(2) }}</span>
          <span class="created-at">{{ o.createdAt }}</span>
        </div>
        <div class="fulfillment-row">
          <span>履约状态</span>
          <span :class="o.fulfillmentStatus === 2 ? 'ready-text' : o.fulfillmentStatus === 0 ? 'warning-text' : ''">
            {{ fulfillmentStatus[o.fulfillmentStatus] || '待填写地址' }}
          </span>
        </div>
        <div v-if="o.status === 0" class="order-actions">
          <button class="btn btn-success btn-sm" @click="doPay(o.orderNo)">立即支付</button>
          <button class="btn btn-outline btn-sm" @click="doCancel(o.orderNo)">取消订单</button>
        </div>
      </div>
    </div>

    <div v-if="editingOrderNo" class="modal-overlay" @click.self="stopAddressEdit">
      <div class="modal-card">
        <div class="modal-head"><h2>填写收货地址</h2><button class="icon-button" @click="stopAddressEdit">×</button></div>
        <p class="modal-note">订单抢购成功后仍可补充地址，保存后商家会看到最新收货信息。</p>
        <div v-if="addresses.length === 0" class="empty-address">
          暂无地址，请先 <router-link to="/addresses">新增收货地址</router-link>
        </div>
        <label v-for="address in addresses" :key="address.id" class="address-option" :class="{selected:selectedAddressId===address.id}">
          <input v-model="selectedAddressId" type="radio" :value="address.id">
          <span><b>{{ address.receiverName }} {{ address.receiverPhone }}</b><small>{{ address.province }} {{ address.city }} {{ address.district }} {{ address.detail }}</small></span>
        </label>
        <div v-if="addressError" class="error-message">{{ addressError }}</div>
        <div class="modal-actions">
          <router-link to="/addresses" class="btn btn-outline btn-sm">管理地址</router-link>
          <button class="btn btn-primary btn-sm" :disabled="!selectedAddressId" @click="saveAddress">保存地址</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.error-message{color:var(--danger);margin-bottom:12px;font-size:14px}.address-alert{display:flex;gap:10px;align-items:baseline;padding:13px 15px;margin-bottom:16px;border-left:3px solid var(--warning);background:var(--primary-light);font-size:13px}.address-alert span{color:var(--text-light)}
.order-card{padding:16px;margin-bottom:12px}.order-head,.order-foot,.fulfillment-row{display:flex;justify-content:space-between;align-items:center}.product-name{font-weight:600}.order-no{font-size:13px;color:var(--text-light);margin:8px 0}.shipping-line{display:flex;justify-content:space-between;gap:12px;align-items:center;font-size:13px;color:var(--text-light);background:var(--bg);padding:9px 10px;border-radius:6px;margin:8px 0;line-height:1.5}.shipping-line strong{color:var(--text)}.shipping-line.missing{color:var(--warning)}.shipping-line.missing span{flex:1}.amount{font-size:18px;font-weight:700;color:var(--primary)}.created-at{font-size:12px;color:var(--text-light)}.fulfillment-row{font-size:12px;color:var(--text-light);margin-top:10px}.warning-text{color:var(--warning)}.ready-text{color:var(--success)}.order-actions{display:flex;gap:8px;margin-top:12px}.modal-overlay{position:fixed;inset:0;background:rgba(0,0,0,.4);z-index:200;display:flex;align-items:center;justify-content:center;padding:20px}.modal-card{background:var(--card-bg);border-radius:var(--radius);box-shadow:0 8px 40px rgba(0,0,0,.15);padding:22px;width:100%;max-width:520px}.modal-head{display:flex;justify-content:space-between;align-items:center}.modal-head h2{font-size:18px;margin:0}.icon-button{border:0;background:none;font-size:26px;line-height:1;color:var(--text-light);cursor:pointer}.modal-note{font-size:13px;color:var(--text-light);line-height:1.5}.address-option{display:flex;align-items:flex-start;gap:10px;border:1px solid var(--border);border-radius:6px;padding:10px;margin-top:7px;cursor:pointer}.address-option.selected{border-color:var(--primary);background:var(--primary-light)}.address-option input{margin-top:3px}.address-option span{min-width:0}.address-option b,.address-option small{display:block}.address-option small{color:var(--text-light);margin-top:3px;line-height:1.4}.empty-address{padding:14px;border:1px dashed var(--border);font-size:13px;color:var(--text-light);text-align:center}.modal-actions{display:flex;justify-content:flex-end;gap:8px;margin-top:18px}@media(max-width:600px){.shipping-line{align-items:flex-start;flex-direction:column}.address-alert{display:block}.address-alert span{display:block;margin-top:4px}}
</style>
