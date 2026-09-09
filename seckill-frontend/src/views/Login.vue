<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthShell from '../components/AuthShell.vue'
import { getCaptcha, sendEmailCodeApi } from '../api/auth'
import { useUserStore } from '../stores/user'
import { useEmailCodeCooldown } from '../composables/useEmailCodeCooldown'

const router=useRouter(),route=useRoute(),store=useUserStore()
const mode=ref('password'),loading=ref(false),error=ref('')
const { sending, countdown, begin, fail, finish } = useEmailCodeCooldown()
const form=reactive({email:'',password:'',code:'',captcha:'',captchaKey:''}),captchaImage=ref('')
async function refreshCaptcha(){try{const r=await getCaptcha();form.captchaKey=r.data.key;captchaImage.value=r.data.image;form.captcha=''}catch{error.value='验证码加载失败，请确认 Redis 和后端服务已启动'}}
function validEmail(){return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email)}
async function sendCode(){if(sending.value||countdown.value>0)return;error.value='';if(!validEmail())return error.value='请输入正确的邮箱';if(!form.captcha)return error.value='发送邮件前请填写图形验证码';if(!begin())return;try{await sendEmailCodeApi({email:form.email,purpose:'login',captcha:form.captcha,captchaKey:form.captchaKey});await refreshCaptcha()}catch(e){error.value=e.message;await refreshCaptcha();fail()}finally{finish()}}
async function submit(){error.value='';if(!validEmail())return error.value='请输入正确的邮箱';if(mode.value==='password'&&!form.password)return error.value='请输入密码';if(mode.value==='code'&&!form.code)return error.value='请输入邮箱验证码';if(!form.captcha)return error.value='请输入图形验证码';loading.value=true;try{await store.login({...form},mode.value);await router.replace(String(route.query.redirect||'/'))}catch(e){error.value=e.message||'登录失败';await refreshCaptcha()}finally{loading.value=false}}
onMounted(refreshCaptcha)
</script>
<template><AuthShell eyebrow="Member access" title="登录闪购" subtitle="验证身份，继续参与限时抢购">
  <div class="auth-tabs"><button :class="{active:mode==='password'}" @click="mode='password'">密码登录</button><button :class="{active:mode==='code'}" @click="mode='code'">邮箱验证码</button></div>
  <form class="auth-form" @submit.prevent="submit"><div v-if="error" class="auth-alert">{{error}}</div>
    <div class="field"><label for="login-email">邮箱</label><input id="login-email" v-model.trim="form.email" type="email" autocomplete="email" placeholder="name@example.com"></div>
    <div v-if="mode==='password'" class="field"><label for="login-password">密码</label><input id="login-password" v-model="form.password" type="password" autocomplete="current-password" placeholder="输入登录密码"></div>
    <div v-else class="field"><label for="login-code">邮箱验证码</label><div class="code-row"><input id="login-code" v-model.trim="form.code" inputmode="numeric" maxlength="6" placeholder="6位验证码"><button class="send-code" type="button" :disabled="sending||countdown>0" @click="sendCode">{{countdown>0?`${countdown}s 后重发`:'获取验证码'}}</button></div></div>
    <div class="field"><label for="login-captcha">图形验证码</label><div class="inline-field"><input id="login-captcha" v-model.trim="form.captcha" maxlength="4" placeholder="输入右侧字符"><img class="captcha-image" :src="captchaImage" alt="点击更换验证码" @click="refreshCaptcha"></div></div>
    <button class="auth-submit" :disabled="loading">{{loading?'正在验证…':'进入秒杀会场'}}</button>
  </form><div class="auth-links"><router-link to="/forgot-password">忘记密码？</router-link><span>没有账号？ <router-link to="/register">立即注册</router-link></span></div>
</AuthShell></template>
