<script setup>
defineProps({ open: Boolean, reason: String })
defineEmits(['cancel','confirm'])
</script>
<template>
  <Transition name="session-dialog">
    <div v-if="open" class="session-backdrop">
      <section class="session-card" role="dialog" aria-modal="true" aria-labelledby="session-title">
        <span class="session-clock">00</span>
        <p class="session-kicker">SESSION ENDED</p>
        <h2 id="session-title">{{ reason === 'idle' ? '你暂时离开太久了' : '登录状态已过期' }}</h2>
        <p>为保护账号，本次会话已经安全结束。重新登录后可以回到当前页面。</p>
        <div><button class="session-muted" @click="$emit('cancel')">稍后登录</button><button class="session-primary" autofocus @click="$emit('confirm')">重新登录</button></div>
      </section>
    </div>
  </Transition>
</template>
<style scoped>
.session-backdrop{position:fixed;inset:0;z-index:1000;display:grid;place-items:center;padding:20px;background:rgba(5,15,24,.72);backdrop-filter:blur(8px)}.session-card{width:min(100%,420px);padding:36px;border-radius:16px;background:#fff;text-align:center;box-shadow:0 30px 80px rgba(0,0,0,.35)}.session-clock{display:grid;place-items:center;width:58px;height:58px;margin:0 auto 18px;border:5px solid #ff5b32;border-radius:50%;color:#ff5b32;font:800 18px ui-monospace,monospace}.session-kicker{color:#e64a22;font:800 10px ui-monospace,monospace;letter-spacing:.2em}.session-card h2{margin:9px 0;color:#132a3b}.session-card>p:last-of-type{color:#71808b;line-height:1.7;font-size:14px}.session-card div{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:24px}.session-card button{height:44px;border-radius:8px;font-weight:750}.session-muted{background:#eef2f5;color:#374b5a}.session-primary{background:#ff5b32;color:white}.session-dialog-enter-active,.session-dialog-leave-active{transition:opacity .18s}.session-dialog-enter-from,.session-dialog-leave-to{opacity:0}
</style>
