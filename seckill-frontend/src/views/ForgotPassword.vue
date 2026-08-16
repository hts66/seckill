<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import AuthShell from '../components/AuthShell.vue'
import { getCaptcha, resetPasswordApi, sendEmailCodeApi } from '../api/auth'
const router=useRouter(),loading=ref(false),sending=ref(false),countdown=ref(0),error=ref(''),success=ref('')
const form=reactive({email:'',newPassword:'',confirmPassword:'',code:'',captcha:'',captchaKey:''}),captchaImage=ref('')
async function refreshCaptcha(){try{const r=await getCaptcha();form.captchaKey=r.data.key;captchaImage.value=r.data.image;form.captcha=''}catch{error.value='验证码加载失败，请确认服务已启动'}}
const validEmail=()=>/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email)
async function sendCode(){error.value='';if(!validEmail())return error.value='请输入正确的邮箱';if(!form.captcha)return error.value='发送邮件前请填写图形验证码';sending.value=true;try{const r=await sendEmailCodeApi({email:form.email,purpose:'reset',captcha:form.captcha,captchaKey:form.captchaKey});success.value=r.message;countdown.value=60;const timer=setInterval(()=>{countdown.value--;if(countdown.value<=0)clearInterval(timer)},1000);await refreshCaptcha()}catch(e){error.value=e.message;await refreshCaptcha()}finally{sending.value=false}}
async function submit(){error.value='';success.value='';if(!validEmail())return error.value='请输入正确的邮箱';if(!/^(?=.*[A-Za-z])(?=.*\d).{8,64}$/.test(form.newPassword))return error.value='新密码至少8位，且同时包含字母和数字';if(form.newPassword!==form.confirmPassword)return error.value='两次输入的密码不一致';if(!form.code||!form.captcha)return error.value='请填写邮箱验证码和图形验证码';loading.value=true;try{await resetPasswordApi(form);success.value='密码已重置，即将返回登录';setTimeout(()=>router.replace('/login'),1000)}catch(e){error.value=e.message||'重置失败';await refreshCaptcha()}finally{loading.value=false}}
onMounted(refreshCaptcha)
</script>
<template><AuthShell eyebrow="Account recovery" title="重置登录密码" subtitle="验证注册邮箱，为账号设置新密码">
  <form class="auth-form" @submit.prevent="submit"><div v-if="error" class="auth-alert">{{error}}</div><div v-if="success" class="auth-alert auth-success">{{success}}</div>
    <div class="field"><label>注册邮箱</label><input v-model.trim="form.email" type="email" autocomplete="email" placeholder="name@example.com"></div>
    <div class="field"><label>新密码</label><input v-model="form.newPassword" type="password" autocomplete="new-password" placeholder="至少8位，包含字母和数字"></div>
    <div class="field"><label>确认新密码</label><input v-model="form.confirmPassword" type="password" autocomplete="new-password" placeholder="再次输入新密码"></div>
    <div class="field"><label>图形验证码</label><div class="inline-field"><input v-model.trim="form.captcha" maxlength="4" placeholder="输入右侧字符"><img class="captcha-image" :src="captchaImage" alt="点击更换验证码" @click="refreshCaptcha"></div></div>
    <div class="field"><label>邮箱验证码</label><div class="code-row"><input v-model.trim="form.code" maxlength="6" inputmode="numeric" placeholder="6位验证码"><button class="send-code" type="button" :disabled="sending||countdown>0" @click="sendCode">{{countdown>0?`${countdown}s 后重发`:'发送验证码'}}</button></div></div>
    <button class="auth-submit" :disabled="loading">{{loading?'正在重置…':'确认重置密码'}}</button>
  </form><div class="auth-links"><router-link to="/login">返回登录</router-link><router-link to="/register">注册新账号</router-link></div>
</AuthShell></template>
