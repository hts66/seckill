<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import AuthShell from '../components/AuthShell.vue'
import { getCaptcha, sendEmailCodeApi } from '../api/auth'
import { useUserStore } from '../stores/user'
const router=useRouter(),store=useUserStore(),loading=ref(false),sending=ref(false),countdown=ref(0),error=ref('')
const form=reactive({email:'',username:'',password:'',confirmPassword:'',code:'',captcha:'',captchaKey:''}),captchaImage=ref('')
async function refreshCaptcha(){try{const r=await getCaptcha();form.captchaKey=r.data.key;captchaImage.value=r.data.image;form.captcha=''}catch{error.value='验证码加载失败，请确认服务已启动'}}
const validEmail=()=>/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email)
async function sendCode(){error.value='';if(!validEmail())return error.value='请输入正确的邮箱';if(!form.captcha)return error.value='发送邮件前请填写图形验证码';sending.value=true;try{await sendEmailCodeApi({email:form.email,purpose:'register',captcha:form.captcha,captchaKey:form.captchaKey});countdown.value=60;const timer=setInterval(()=>{countdown.value--;if(countdown.value<=0)clearInterval(timer)},1000);await refreshCaptcha()}catch(e){error.value=e.message;await refreshCaptcha()}finally{sending.value=false}}
async function submit(){error.value='';if(!validEmail())return error.value='请输入正确的邮箱';if(form.username.trim().length<2)return error.value='用户名至少2个字符';if(!/^(?=.*[A-Za-z])(?=.*\d).{8,64}$/.test(form.password))return error.value='密码至少8位，且同时包含字母和数字';if(form.password!==form.confirmPassword)return error.value='两次输入的密码不一致';if(!form.code||!form.captcha)return error.value='请填写邮箱验证码和图形验证码';loading.value=true;try{const {confirmPassword,...payload}=form;await store.register(payload);router.replace('/')}catch(e){error.value=e.message||'注册失败';await refreshCaptcha()}finally{loading.value=false}}
onMounted(refreshCaptcha)
</script>
<template><AuthShell eyebrow="Create account" title="注册闪购账号" subtitle="邮箱验证后即可参与抢购">
  <form class="auth-form" @submit.prevent="submit"><div v-if="error" class="auth-alert">{{error}}</div>
    <div class="field"><label>邮箱</label><input v-model.trim="form.email" type="email" autocomplete="email" placeholder="name@example.com"></div>
    <div class="field"><label>用户名</label><input v-model.trim="form.username" autocomplete="nickname" maxlength="20" placeholder="2—20个字符"></div>
    <div class="field"><label>密码</label><input v-model="form.password" type="password" autocomplete="new-password" placeholder="至少8位，包含字母和数字"></div>
    <div class="field"><label>确认密码</label><input v-model="form.confirmPassword" type="password" autocomplete="new-password" placeholder="再次输入密码"></div>
    <div class="field"><label>图形验证码</label><div class="inline-field"><input v-model.trim="form.captcha" maxlength="4" placeholder="输入右侧字符"><img class="captcha-image" :src="captchaImage" alt="点击更换验证码" @click="refreshCaptcha"></div></div>
    <div class="field"><label>邮箱验证码</label><div class="code-row"><input v-model.trim="form.code" maxlength="6" inputmode="numeric" placeholder="6位验证码"><button class="send-code" type="button" :disabled="sending||countdown>0" @click="sendCode">{{countdown>0?`${countdown}s 后重发`:'发送验证码'}}</button></div></div>
    <button class="auth-submit" :disabled="loading">{{loading?'正在创建…':'创建账号并登录'}}</button>
  </form><div class="auth-links"><router-link to="/">返回商城</router-link><span>已有账号？ <router-link to="/login">直接登录</router-link></span></div>
</AuthShell></template>
