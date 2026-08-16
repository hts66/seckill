<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import ImageUpload from '../components/ImageUpload.vue'
import {
  getProducts, createProduct, updateProduct, deleteProduct,
  uploadImage, uploadImages, deleteUploadedImage
} from '../api/product'
import {
  getActivities, createActivity, updateActivity, deleteActivity,
  getActivityItems, createSeckillItem, updateSeckillItem, deleteSeckillItem,
  warmUpActivity, getRedisStock, getAllOrders, shipOrder
} from '../api/seckill'

// ============ Tab 切换 ============
const tab = ref('products')

// ============ 商品管理 ============
const products = ref([])
const productForm = ref({
  name: '', title: '', description: '', images: [], price: null, stock: null
})
const editingProductId = ref(null)
const showProductForm = ref(false)

async function loadProducts() {
  try { const res = await getProducts(); products.value = res.data || [] } catch (e) { console.error(e) }
}

const originalImages = ref([])  // 编辑时已有的图片（取消时不删除）

function deleteImageQuietly(url) {
  return deleteUploadedImage(url).catch(err => {
    console.warn('删除 MinIO 图片失败:', url, err)
  })
}

function handleProductImageRemoved(url) {
  // 本次新上传的图片尚未被商品引用，可以立即清理；
  // 已保存的旧图要等商品更新成功后再删除，避免取消或保存失败后出现裂图。
  if (!originalImages.value.includes(url)) {
    deleteImageQuietly(url)
  }
}

function cancelProductForm() {
  // 仅删除新上传的图片（编辑时已有图片不删）
  const savedUrls = new Set(originalImages.value)
  if (productForm.value.images && productForm.value.images.length > 0) {
    productForm.value.images.forEach(url => {
      if (!savedUrls.has(url)) {
        deleteImageQuietly(url)
      }
    })
  }
  showProductForm.value = false
}

function openCreateProduct() {
  editingProductId.value = null
  originalImages.value = []
  productForm.value = { name: '', title: '', description: '', images: [], price: null, stock: null }
  showProductForm.value = true
}

function openEditProduct(p) {
  editingProductId.value = p.id
  originalImages.value = [...(p.imageList || [])]
  productForm.value = {
    name: p.name, title: p.title, description: p.description,
    images: [...(p.imageList || [])],
    price: p.price, stock: p.stock
  }
  showProductForm.value = true
}

async function saveProduct() {
  try {
    // 清洗数据：空字符串 → null（避免 Jackson 反序列化失败）
    const data = {
      name: productForm.value.name || '',
      title: productForm.value.title || null,
      description: productForm.value.description || null,
      images: productForm.value.images || [],
      price: productForm.value.price !== null && productForm.value.price !== '' && !isNaN(productForm.value.price)
        ? Number(productForm.value.price) : null,
      stock: productForm.value.stock !== null && productForm.value.stock !== '' && !isNaN(productForm.value.stock)
        ? Number(productForm.value.stock) : null,
    }
    // 基本校验
    if (!data.name.trim()) { alert('请输入商品名称'); return }
    if (data.price == null || data.price <= 0) { alert('请输入有效价格'); return }

    const removedOriginalImages = editingProductId.value
      ? originalImages.value.filter(url => !data.images.includes(url))
      : []

    if (editingProductId.value) {
      await updateProduct(editingProductId.value, data)
    } else {
      await createProduct(data)
    }

    // 数据库更新成功后，旧图片已不再被商品引用，此时才安全地清理 MinIO。
    removedOriginalImages.forEach(deleteImageQuietly)
    showProductForm.value = false
    loadProducts()
  } catch (e) {
    // 尝试提取服务器返回的详细错误信息
    const msg = e?.response?.data?.message || e.message || '未知错误'
    alert('保存失败: ' + msg)
  }
}

async function removeProduct(id) {
  if (!confirm('确定删除该商品吗？')) return
  try { await deleteProduct(id); loadProducts() } catch (e) { alert(e.message) }
}

// ============ 活动管理 ============
const activities = ref([])
const activityItems = ref({})
const redisStocks = ref({})
const showActivityForm = ref(false)
const editingActivityId = ref(null)
const activityForm = ref({
  name: '', description: '', previewTime: '', startTime: '', endTime: '',
  items: []
})

function formatLocalDatetime(date) {
  if (!date) return ''
  // 如果是 LocalDateTime 类型 (数组格式 [year,month,day,hour,min,sec])
  if (Array.isArray(date)) {
    const [y, m, d, h = 0, min = 0, s = 0] = date
    return `${y}-${String(m).padStart(2,'0')}-${String(d).padStart(2,'0')}T${String(h).padStart(2,'0')}:${String(min).padStart(2,'0')}`
  }
  // 字符串格式兼容
  if (typeof date === 'string') return date.substring(0, 16)
  return ''
}

async function loadActivities() {
  try {
    const res = await getActivities()
    activities.value = res.data || []
    await refreshActivityStocks()
  } catch (e) { console.error(e) }
}

async function refreshActivityStocks() {
  const nextItems = {}
  const nextStocks = { ...redisStocks.value }
  await Promise.all(activities.value.map(async (activity) => {
    try {
      const itemRes = await getActivityItems(activity.id)
      const items = itemRes.data || []
      nextItems[activity.id] = items
      await Promise.all(items.map(async (item) => {
        try {
          const stockRes = await getRedisStock(item.id)
          nextStocks[item.id] = stockRes.data
        } catch (e) {
          // Redis key may not exist before the activity is warmed up.
          nextStocks[item.id] = null
        }
      }))
    } catch (e) { console.error(e) }
  }))
  activityItems.value = nextItems
  redisStocks.value = nextStocks
}

function realtimeStock(item) {
  const value = redisStocks.value[item.id]
  return value === null || value === undefined || value < 0 ? '未预热' : value
}

let stockTimer

function toLocalDatetimeStr(date) {
  // 返回 datetime-local 格式：YYYY-MM-DDTHH:mm
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth()+1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function openCreateActivity() {
  editingActivityId.value = null
  const now = new Date()
  // 默认：立即可见预热，5分钟后开抢，2小时后结束
  activityForm.value = {
    name: '', description: '',
    previewTime: toLocalDatetimeStr(now),
    startTime: toLocalDatetimeStr(new Date(now.getTime() + 5 * 60000)),
    endTime: toLocalDatetimeStr(new Date(now.getTime() + 2 * 3600000)),
    items: []
  }
  showActivityForm.value = true
}

function openEditActivity(a) {
  editingActivityId.value = a.id
  activityForm.value = {
    name: a.name, description: a.description || '',
    previewTime: formatLocalDatetime(a.previewTime),
    startTime: formatLocalDatetime(a.startTime),
    endTime: formatLocalDatetime(a.endTime),
    items: []
  }
  loadActivityItems(a.id)
  showActivityForm.value = true
}

async function loadActivityItems(activityId) {
  try { const res = await getActivityItems(activityId); activityForm.value.items = res.data || [] } catch (e) { console.error(e) }
}

function addItemRow() {
  activityForm.value.items.push({ productId: null, seckillPrice: null, stock: null, limitPerUser: 1 })
}

function removeItemRow(index) {
  activityForm.value.items.splice(index, 1)
}

async function saveActivity() {
  try {
    // datetime-local 格式 "2026-07-23T10:30" 直接发送，后端 LocalDateTime 可解析
    // 不能转 toISOString() 否则时区会偏移
    const data = {
      ...activityForm.value,
      previewTime: activityForm.value.previewTime ? activityForm.value.previewTime + ':00' : null,
      startTime: activityForm.value.startTime ? activityForm.value.startTime + ':00' : null,
      endTime: activityForm.value.endTime ? activityForm.value.endTime + ':00' : null,
    }
    if (!data.name.trim()) { alert('请输入活动名称'); return }
    if (!data.previewTime || !data.startTime || !data.endTime) { alert('请填写完整时间'); return }

    if (editingActivityId.value) {
      await updateActivity(editingActivityId.value, data)
    } else {
      await createActivity(data)
    }
    showActivityForm.value = false
    loadActivities()
  } catch (e) {
    const msg = e?.response?.data?.message || e.message || '未知错误'
    alert('保存失败: ' + msg)
  }
}

async function removeActivity(id) {
  if (!confirm('确定删除该活动吗？')) return
  try { await deleteActivity(id); loadActivities() } catch (e) { alert(e.message) }
}

// ============ 工具 ============
const stockResult = ref('')
const stockItemId = ref(null)
async function doWarmUp(activityId) {
  try { await warmUpActivity(activityId); stockResult.value = '预热成功' } catch (e) { stockResult.value = '失败: ' + e.message }
}
async function checkStock(itemId) {
  try {
    const res = await getRedisStock(itemId)
    stockResult.value = `itemId=${itemId} Redis库存=${res.data}`
  } catch (e) { stockResult.value = '查询失败' }
}

const orders = ref([])
async function queryOrders() {
  try {
    const res = await getAllOrders()
    orders.value = res.data || []
  } catch (e) {
    console.error(e)
    orders.value = []
    alert('订单查询失败: ' + (e?.response?.data?.message || e.message || '未知错误'))
  }
}

async function doShipOrder(orderNo) {
  if (!confirm('确认将这个订单标记为已发货吗？')) return
  try {
    await shipOrder(orderNo)
    await queryOrders()
  } catch (e) {
    alert('发货失败: ' + (e?.response?.data?.message || e.message || '未知错误'))
  }
}

const statusMap = ['未开始', '预热中', '进行中', '已结束']
const orderStatusMap = ['待支付', '已支付', '已取消', '超时取消']
const fulfillmentStatusMap = ['待填写地址', '待发货', '已发货', '已收货']

onMounted(() => {
  loadProducts()
  loadActivities()
  stockTimer = window.setInterval(() => {
    if (activities.value.length > 0) refreshActivityStocks()
  }, 3000)
})

onUnmounted(() => {
  if (stockTimer) window.clearInterval(stockTimer)
})
</script>

<template>
  <div class="container fade-in" style="max-width:900px;">
    <h1 class="page-title" style="margin-top:20px;">⚙️ 后台管理</h1>

    <!-- Tab 切换 -->
    <div style="display:flex;gap:8px;margin-bottom:20px;flex-wrap:wrap;">
      <button class="btn" :class="tab==='products'?'btn-primary':'btn-outline'" @click="tab='products'">
        📦 商品管理
      </button>
      <button class="btn" :class="tab==='activities'?'btn-primary':'btn-outline'" @click="tab='activities'">
        🎯 活动管理
      </button>
      <button class="btn" :class="tab==='tools'?'btn-primary':'btn-outline'" @click="tab='tools'">
        🔧 运营工具
      </button>
    </div>

    <!-- ==================== 商品管理 ==================== -->
    <div v-if="tab==='products'">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;">
        <span style="font-size:14px;color:var(--text-light);">共 {{ products.length }} 个商品</span>
        <button class="btn btn-primary btn-sm" @click="openCreateProduct">+ 新建商品</button>
      </div>

      <!-- 商品列表 -->
      <div v-if="products.length===0" class="empty-state"><p>暂无商品</p></div>
      <div v-for="p in products" :key="p.id" class="card" style="padding:14px;margin-bottom:10px;display:flex;gap:14px;align-items:center;">
        <!-- 封面图 -->
        <div style="width:64px;height:64px;background:var(--border);border-radius:8px;flex-shrink:0;overflow:hidden;">
          <img v-if="p.imageList&&p.imageList.length" :src="p.imageList[0]" style="width:100%;height:100%;object-fit:cover;" />
          <div v-else style="width:100%;height:100%;display:flex;align-items:center;justify-content:center;font-size:22px;color:var(--text-light);">📷</div>
        </div>
        <div style="flex:1;min-width:0;">
          <div style="font-weight:600;">{{ p.name }}</div>
          <div style="font-size:12px;color:var(--text-light);">{{ p.title || '-' }}</div>
          <div style="font-size:13px;margin-top:2px;">
            <span style="color:var(--primary);font-weight:700;">￥{{ Number(p.price||0).toFixed(2) }}</span>
            <span style="color:var(--text-light);margin-left:8px;">商品原始库存: {{ p.stock }}</span>
            <span v-if="p.imageList&&p.imageList.length" style="color:var(--text-light);margin-left:8px;">
              🖼️ {{ p.imageList.length }}图
            </span>
          </div>
        </div>
        <div style="display:flex;gap:6px;flex-shrink:0;">
          <button class="btn btn-outline btn-sm" @click="openEditProduct(p)">编辑</button>
          <button class="btn btn-danger btn-sm" @click="removeProduct(p.id)">删除</button>
        </div>
      </div>

      <!-- 商品表单弹窗 -->
      <div v-if="showProductForm" class="modal-overlay" @click.self="cancelProductForm">
        <div class="modal-card">
          <h2 style="margin-bottom:18px;">{{ editingProductId ? '编辑商品' : '新建商品' }}</h2>

          <label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">商品图片</label>
          <ImageUpload
            v-model="productForm.images"
            :maxCount="5"
            style="margin-bottom:12px;"
            @image-removed="handleProductImageRemoved"
          />

          <label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">商品名称 *</label>
          <input v-model="productForm.name" class="input" placeholder="如 iPhone 16 Pro Max" style="margin-bottom:10px;">

          <label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">标题/标语</label>
          <input v-model="productForm.title" class="input" placeholder="如 旗舰直降，限时秒杀" style="margin-bottom:10px;">

          <label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">商品描述</label>
          <textarea v-model="productForm.description" class="input" rows="3" placeholder="详细描述..." style="margin-bottom:10px;"></textarea>

          <div style="display:flex;gap:12px;margin-bottom:12px;">
            <div style="flex:1;">
              <label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">价格 *</label>
              <input v-model.number="productForm.price" class="input" type="number" step="0.01" placeholder="0.00">
            </div>
            <div style="flex:1;">
              <label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">库存 *</label>
              <input v-model.number="productForm.stock" class="input" type="number" placeholder="0">
            </div>
          </div>

          <div style="display:flex;gap:8px;justify-content:flex-end;">
            <button class="btn btn-outline btn-sm" @click="cancelProductForm">取消</button>
            <button class="btn btn-primary btn-sm" @click="saveProduct">保存</button>
          </div>
        </div>
      </div>
    </div>

    <!-- ==================== 活动管理 ==================== -->
    <div v-if="tab==='activities'">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;">
        <span style="font-size:14px;color:var(--text-light);">共 {{ activities.length }} 个活动</span>
        <button class="btn btn-primary btn-sm" @click="openCreateActivity">+ 新建活动</button>
      </div>

      <div v-if="activities.length===0" class="empty-state"><p>暂无活动</p></div>
      <div v-for="a in activities" :key="a.id" class="card" style="padding:14px;margin-bottom:10px;">
        <div style="display:flex;justify-content:space-between;align-items:center;">
          <div>
            <div style="font-weight:600;">{{ a.name }}</div>
            <div style="font-size:12px;color:var(--text-light);">
              🕐 预热：{{ formatLocalDatetime(a.previewTime) || '-' }}
              | 🔥 开抢：{{ formatLocalDatetime(a.startTime) || '-' }}
              | ⏹ 结束：{{ formatLocalDatetime(a.endTime) || '-' }}
            </div>
            <div style="margin-top:2px;">
              <span class="badge" :class="a.status===2?'badge-green':a.status===1?'badge-yellow':a.status===3?'badge-gray':'badge-red'">
                {{ statusMap[a.status] || '未知' }}
              </span>
              <span style="font-size:12px;color:var(--text-light);margin-left:4px;">{{ a.description || '' }}</span>
            </div>
          </div>
          <div style="display:flex;gap:6px;flex-shrink:0;">
            <button class="btn btn-outline btn-sm" @click="openEditActivity(a)">编辑</button>
            <button class="btn btn-outline btn-sm" @click="doWarmUp(a.id)">预热</button>
            <button class="btn btn-danger btn-sm" @click="removeActivity(a.id)">删除</button>
          </div>
        </div>
        <div v-if="(activityItems[a.id] || []).length" style="margin-top:10px;padding-top:8px;border-top:1px solid var(--border);font-size:12px;color:var(--text-light);">
          <span v-for="item in activityItems[a.id]" :key="item.id" style="margin-right:16px;">
            {{ item.productName || ('商品 #' + item.productId) }}
            <strong style="color:var(--primary);">秒杀剩余库存 {{ realtimeStock(item) }}</strong>
            <span style="margin-left:4px;">/ 原始库存 {{ item.stock }}</span>
          </span>
        </div>
      </div>

      <!-- 活动表单弹窗 -->
      <div v-if="showActivityForm" class="modal-overlay" @click.self="showActivityForm=false">
        <div class="modal-card" style="max-width:700px;">
          <h2 style="margin-bottom:18px;">{{ editingActivityId ? '编辑活动' : '新建秒杀活动' }}</h2>

          <label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">活动名称 *</label>
          <input v-model="activityForm.name" class="input" placeholder="如 618数码狂欢节" style="margin-bottom:10px;">

          <label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">活动描述</label>
          <input v-model="activityForm.description" class="input" placeholder="限时秒杀，手慢无！" style="margin-bottom:10px;">

          <div style="display:flex;gap:12px;margin-bottom:12px;">
            <div style="flex:1;">
              <label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">
                🕐 预热展示时间
              </label>
              <input v-model="activityForm.previewTime" class="input" type="datetime-local">
              <span style="font-size:11px;color:var(--text-light);">用户可见倒计时但不可抢</span>
            </div>
          </div>

          <div style="display:flex;gap:12px;margin-bottom:12px;">
            <div style="flex:1;">
              <label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">
                🔥 开抢时间 *
              </label>
              <input v-model="activityForm.startTime" class="input" type="datetime-local">
            </div>
            <div style="flex:1;">
              <label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">
                ⏹ 结束时间 *
              </label>
              <input v-model="activityForm.endTime" class="input" type="datetime-local">
            </div>
          </div>

          <!-- 秒杀项 -->
          <div style="margin-bottom:12px;">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px;">
              <label style="font-size:13px;font-weight:600;">秒杀商品列表（选填）</label>
              <button class="btn btn-outline btn-sm" @click="addItemRow">+ 添加</button>
            </div>
            <div v-if="activityForm.items.length===0" style="font-size:12px;color:var(--text-light);">暂无秒杀商品，点击上方"添加"关联商品</div>
            <div v-for="(item, i) in activityForm.items" :key="i" style="display:flex;gap:8px;align-items:center;margin-bottom:6px;padding:8px;background:var(--bg);border-radius:8px;">
              <select v-model.number="item.productId" class="input" style="width:auto;flex:1;font-size:12px;padding:6px;">
                <option :value="null" disabled>选择商品</option>
                <option v-for="p in products" :key="p.id" :value="p.id">{{ p.name }}</option>
              </select>
              <input v-model.number="item.seckillPrice" class="input" type="number" step="0.01" placeholder="秒杀价" style="width:80px;font-size:12px;padding:6px;">
              <input v-model.number="item.stock" class="input" type="number" placeholder="库存" style="width:60px;font-size:12px;padding:6px;">
              <input v-model.number="item.limitPerUser" class="input" type="number" placeholder="限购" style="width:50px;font-size:12px;padding:6px;">
              <button class="btn btn-danger btn-sm" style="padding:4px 8px;font-size:11px;" @click="removeItemRow(i)">×</button>
            </div>
          </div>

          <div style="display:flex;gap:8px;justify-content:flex-end;">
            <button class="btn btn-outline btn-sm" @click="showActivityForm=false">取消</button>
            <button class="btn btn-primary btn-sm" @click="saveActivity">保存</button>
          </div>
        </div>
      </div>
    </div>

    <!-- ==================== 运营工具 ==================== -->
    <div v-if="tab==='tools'">
      <div class="card" style="padding:20px;margin-bottom:16px;">
        <h2 style="font-size:16px;margin-bottom:12px;">🔥 活动预热（加载库存到 Redis）</h2>
        <div v-for="a in activities" :key="a.id" style="margin-bottom:6px;">
          <button class="btn btn-outline btn-sm" @click="doWarmUp(a.id)">
            预热「{{ a.name }}」(ID:{{ a.id }})
          </button>
        </div>
        <div v-if="stockResult" style="margin-top:8px;font-size:13px;color:var(--success);">{{ stockResult }}</div>
      </div>

      <div class="card" style="padding:20px;margin-bottom:16px;">
        <h2 style="font-size:16px;margin-bottom:12px;">📊 查询 Redis 库存</h2>
        <div style="display:flex;gap:8px;align-items:center;">
          <input v-model.number="stockItemId" class="input" type="number" placeholder="输入秒杀项 itemId" style="width:160px;">
          <button class="btn btn-outline btn-sm" @click="checkStock(stockItemId||1)">查询</button>
        </div>
        <div v-if="stockResult" style="margin-top:8px;font-size:13px;color:var(--success);">{{ stockResult }}</div>
      </div>

      <div class="card" style="padding:20px;">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;">
          <h2 style="font-size:16px;margin:0;">📋 所有订单</h2>
          <button class="btn btn-outline btn-sm" @click="queryOrders">刷新</button>
        </div>
        <div v-if="orders.length===0" style="color:var(--text-light);font-size:14px;">点击刷新查询</div>
        <table v-else style="width:100%;font-size:13px;border-collapse:collapse;">
          <thead>
            <tr style="text-align:left;border-bottom:2px solid var(--border);">
              <th style="padding:8px;">ID</th>
              <th style="padding:8px;">订单号</th>
              <th style="padding:8px;">买家</th>
              <th style="padding:8px;">用户ID</th>
              <th style="padding:8px;">收货信息</th>
              <th style="padding:8px;">金额</th>
              <th style="padding:8px;">状态</th>
              <th style="padding:8px;">履约</th>
              <th style="padding:8px;">操作</th>
              <th style="padding:8px;">时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="o in orders" :key="o.id" style="border-bottom:1px solid var(--border);">
              <td style="padding:8px;">{{ o.id }}</td>
              <td style="padding:8px;">{{ o.orderNo?.substring(0,12) }}...</td>
              <td style="padding:8px;">
                <div>{{ o.buyerName || '未知用户' }}</div>
                <div style="font-size:11px;color:var(--text-light);">{{ o.buyerEmail || '-' }}</div>
              </td>
              <td style="padding:8px;">{{ o.userId }}</td>
              <td style="padding:8px;min-width:220px;">
                <template v-if="o.receiverName">
                  <div>{{ o.receiverName }} {{ o.receiverPhone }}</div>
                  <div style="font-size:11px;color:var(--text-light);">{{ o.receiverProvince }} {{ o.receiverCity }} {{ o.receiverDistrict }} {{ o.receiverDetail }}</div>
                </template>
                <span v-else style="color:var(--warning);">待用户填写地址</span>
              </td>
              <td style="padding:8px;color:var(--primary);">￥{{ Number(o.amount||0).toFixed(2) }}</td>
              <td style="padding:8px;">
                <span class="badge" :class="o.status===1?'badge-green':o.status===0?'badge-yellow':'badge-gray'">
                  {{ orderStatusMap[o.status] || '未知' }}
                </span>
              </td>
              <td style="padding:8px;">
                <span class="badge" :class="o.fulfillmentStatus===2?'badge-green':o.fulfillmentStatus===0?'badge-yellow':'badge-gray'">
                  {{ fulfillmentStatusMap[o.fulfillmentStatus] || '待填写地址' }}
                </span>
              </td>
              <td style="padding:8px;">
                <button v-if="o.status===1 && o.fulfillmentStatus===1" class="btn btn-success btn-sm" @click="doShipOrder(o.orderNo)">发货</button>
                <span v-else style="color:var(--text-light);font-size:12px;">-</span>
              </td>
              <td style="padding:8px;">{{ o.createdAt }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.4); z-index: 200;
  display: flex; align-items: center; justify-content: center;
  padding: 20px;
}
.modal-card {
  background: var(--card-bg); border-radius: var(--radius);
  box-shadow: 0 8px 40px rgba(0,0,0,0.15); padding: 24px;
  width: 100%; max-width: 560px; max-height: 80vh; overflow-y: auto;
}
</style>
