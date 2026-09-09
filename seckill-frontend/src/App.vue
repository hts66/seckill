<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SessionExpiredDialog from './components/SessionExpiredDialog.vue'
import CustomerServiceWidget from './components/CustomerServiceWidget.vue'
import { useUserStore } from './stores/user'

const store=useUserStore(),router=useRouter(),route=useRoute(),dialogOpen=ref(false),expiredReason=ref('expired')
const isAuthRoute=()=>['Login','Register','ForgotPassword'].includes(String(route.name))
function handleExpired(event){expiredReason.value=event.detail?.reason||'expired';dialogOpen.value=true}
async function handleLogout(){await store.logout();router.push('/login')}
function confirmRelogin(){dialogOpen.value=false;router.push({path:'/login',query:{redirect:route.fullPath}})}
function cancelRelogin(){dialogOpen.value=false}
onMounted(()=>window.addEventListener('auth-session-expired',handleExpired))
onBeforeUnmount(()=>window.removeEventListener('auth-session-expired',handleExpired))
</script>
<template><div id="app-root">
  <nav v-if="!isAuthRoute()" class="navbar"><router-link to="/" class="logo"><span class="logo-mark">S</span>闪购</router-link><div class="nav-right">
    <template v-if="store.isLoggedIn"><template v-if="!store.isAdmin"><router-link to="/addresses" class="nav-link">收货地址</router-link><router-link to="/orders" class="nav-link">我的订单</router-link></template><router-link v-if="store.isAdmin" to="/admin" class="nav-link">管理台</router-link><span class="nav-user">{{store.user?.username}}</span><button class="btn btn-outline btn-sm" @click="handleLogout">退出</button></template>
    <router-link v-else to="/login" class="btn btn-outline btn-sm">登录</router-link>
  </div></nav>
  <main><router-view/></main>
  <CustomerServiceWidget v-if="store.isLoggedIn && !store.isAdmin"/>
  <SessionExpiredDialog :open="dialogOpen" :reason="expiredReason" @cancel="cancelRelogin" @confirm="confirmRelogin"/>
</div></template>
<style scoped>main{min-height:calc(100vh - 64px)}.logo{display:flex;align-items:center;gap:8px}.logo-mark{display:grid;place-items:center;width:28px;height:28px;background:var(--primary);color:#fff;clip-path:polygon(20% 0,100% 0,72% 44%,96% 44%,25% 100%,42% 57%,8% 57%)}.nav-user{font-size:13px;color:var(--text-light)}</style>
