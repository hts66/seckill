import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'Home', component: () => import('../views/Home.vue') },
  { path: '/item/:itemId', name: 'SeckillDetail', component: () => import('../views/SeckillDetail.vue') },
  { path: '/login', name: 'Login', component: () => import('../views/Login.vue'), meta: { guestOnly: true } },
  { path: '/register', name: 'Register', component: () => import('../views/Register.vue'), meta: { guestOnly: true } },
  { path: '/forgot-password', name: 'ForgotPassword', component: () => import('../views/ForgotPassword.vue'), meta: { guestOnly: true } },
  { path: '/seckill/:itemId', name: 'Seckill', component: () => import('../views/Seckill.vue'), meta: { requiresAuth: true, customerOnly: true } },
  { path: '/orders', name: 'Orders', component: () => import('../views/Orders.vue'), meta: { requiresAuth: true, customerOnly: true } },
  { path: '/addresses', name: 'Addresses', component: () => import('../views/Addresses.vue'), meta: { requiresAuth: true, customerOnly: true } },
  { path: '/admin', name: 'Admin', component: () => import('../views/Admin.vue'), meta: { requiresAuth: true, admin: true } },
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach(to => {
  const loggedIn = Boolean(localStorage.getItem('token'))
  let user = null
  try { user = JSON.parse(localStorage.getItem('user') || 'null') } catch { /* ignored */ }
  if (to.meta.requiresAuth && !loggedIn) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.admin && user?.role !== 1) return '/'
  if (to.meta.customerOnly && user?.role === 1) return '/admin'
  if (to.meta.guestOnly && loggedIn) return '/'
})

export default router
