<script setup>
import { onMounted, ref } from 'vue'
import { createAddress, deleteAddress, getAddresses, setDefaultAddress, updateAddress } from '../api/order'

const addresses = ref([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const editingId = ref(null)
const showForm = ref(false)
const emptyForm = () => ({ receiverName: '', receiverPhone: '', province: '', city: '', district: '', detail: '', isDefault: false })
const form = ref(emptyForm())

async function loadAddresses() {
  loading.value = true
  error.value = ''
  try { addresses.value = (await getAddresses()).data || [] }
  catch (e) { error.value = e.message || '地址加载失败' }
  finally { loading.value = false }
}

function openCreate() {
  editingId.value = null
  form.value = emptyForm()
  showForm.value = true
}

function openEdit(address) {
  editingId.value = address.id
  form.value = {
    receiverName: address.receiverName, receiverPhone: address.receiverPhone,
    province: address.province, city: address.city, district: address.district,
    detail: address.detail, isDefault: Boolean(address.isDefault)
  }
  showForm.value = true
}

async function save() {
  if (!form.value.receiverName.trim() || !/^1[3-9]\d{9}$/.test(form.value.receiverPhone) ||
      !form.value.province.trim() || !form.value.city.trim() || !form.value.district.trim() || !form.value.detail.trim()) {
    alert('请完整填写收货信息，并输入有效手机号')
    return
  }
  saving.value = true
  try {
    if (editingId.value) await updateAddress(editingId.value, form.value)
    else await createAddress(form.value)
    showForm.value = false
    await loadAddresses()
  } catch (e) { alert(e.message || '地址保存失败') }
  finally { saving.value = false }
}

async function makeDefault(id) {
  try { await setDefaultAddress(id); await loadAddresses() }
  catch (e) { alert(e.message || '设置失败') }
}

async function remove(id) {
  if (!confirm('确定删除这个收货地址吗？')) return
  try { await deleteAddress(id); await loadAddresses() }
  catch (e) { alert(e.message || '删除失败') }
}

onMounted(loadAddresses)
</script>

<template>
  <div class="container fade-in" style="max-width:760px;">
    <div class="page-head">
      <div><h1 class="page-title">收货地址</h1><p>管理秒杀订单使用的配送信息</p></div>
      <button class="btn btn-primary btn-sm" @click="openCreate">新增地址</button>
    </div>
    <div v-if="error" class="error-text">{{ error }}</div>
    <div v-if="loading" class="empty-state"><p class="pulse">加载中...</p></div>
    <div v-else-if="addresses.length===0" class="empty-state"><p>暂无收货地址</p><button class="btn btn-primary" @click="openCreate">新增第一个地址</button></div>
    <div v-else class="address-list">
      <article v-for="address in addresses" :key="address.id" class="card address-card">
        <div class="address-main">
          <div class="address-title"><strong>{{ address.receiverName }}</strong><span>{{ address.receiverPhone }}</span><span v-if="address.isDefault" class="badge badge-red">默认</span></div>
          <p>{{ address.province }} {{ address.city }} {{ address.district }} {{ address.detail }}</p>
        </div>
        <div class="address-actions">
          <button v-if="!address.isDefault" class="text-button" @click="makeDefault(address.id)">设为默认</button>
          <button class="text-button" @click="openEdit(address)">编辑</button>
          <button class="text-button danger" @click="remove(address.id)">删除</button>
        </div>
      </article>
    </div>

    <div v-if="showForm" class="modal-overlay" @click.self="showForm=false">
      <div class="modal-card">
        <h2>{{ editingId ? '编辑收货地址' : '新增收货地址' }}</h2>
        <div class="form-grid">
          <label>收货人<input v-model.trim="form.receiverName" class="input" maxlength="50"></label>
          <label>手机号<input v-model.trim="form.receiverPhone" class="input" inputmode="numeric" maxlength="11"></label>
          <label>省份<input v-model.trim="form.province" class="input" maxlength="50"></label>
          <label>城市<input v-model.trim="form.city" class="input" maxlength="50"></label>
          <label>区/县<input v-model.trim="form.district" class="input" maxlength="50"></label>
          <label class="full">详细地址<textarea v-model.trim="form.detail" class="input" rows="3" maxlength="255"></textarea></label>
        </div>
        <label class="default-check"><input v-model="form.isDefault" type="checkbox">设为默认收货地址</label>
        <div class="modal-actions"><button class="btn btn-outline btn-sm" @click="showForm=false">取消</button><button class="btn btn-primary btn-sm" :disabled="saving" @click="save">{{ saving ? '保存中...' : '保存' }}</button></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-head{display:flex;justify-content:space-between;align-items:center;margin:20px 0}.page-title{margin:0}.page-head p{font-size:13px;color:var(--text-light);margin-top:4px}.address-list{display:grid;gap:10px}.address-card{padding:16px;display:flex;justify-content:space-between;gap:20px}.address-title{display:flex;align-items:center;gap:10px;margin-bottom:7px}.address-main p{font-size:13px;color:var(--text-light);line-height:1.6}.address-actions{display:flex;align-items:center;gap:12px;flex-shrink:0}.text-button{background:none;color:var(--primary);font-size:13px}.text-button.danger{color:#e74c3c}.error-text{color:#e74c3c;margin-bottom:12px}.modal-overlay{position:fixed;inset:0;background:rgba(0,0,0,.42);z-index:200;display:grid;place-items:center;padding:20px}.modal-card{width:min(620px,100%);background:#fff;border-radius:8px;padding:24px;max-height:90vh;overflow:auto}.modal-card h2{font-size:20px;margin-bottom:18px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.form-grid label{font-size:13px;font-weight:600}.form-grid .input{margin-top:5px}.form-grid .full{grid-column:1/-1}.default-check{display:flex;gap:8px;align-items:center;margin-top:14px;font-size:13px}.modal-actions{display:flex;justify-content:flex-end;gap:8px;margin-top:20px}@media(max-width:640px){.address-card{display:block}.address-actions{margin-top:12px}.form-grid{grid-template-columns:1fr}.form-grid .full{grid-column:auto}.navbar{padding:0 12px}}
</style>
