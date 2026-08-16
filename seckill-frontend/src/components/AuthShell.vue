<script setup>
defineProps({ eyebrow: String, title: String, subtitle: String })
</script>

<template>
  <div class="auth-stage">
    <section class="auth-story" aria-label="秒杀商城介绍">
      <router-link to="/" class="auth-brand"><span class="brand-bolt">S</span><span>闪购</span></router-link>
      <div class="auth-story-copy">
        <p class="story-kicker">READY · SET · SECURE</p>
        <h1>准时开抢，<br><em>安心登录。</em></h1>
        <p>身份验证、令牌刷新与账号保护都在后台就位。你只需盯住开售的那一秒。</p>
      </div>
      <div class="countdown-track" aria-hidden="true">
        <span v-for="tick in 18" :key="tick" :class="{ major: tick % 6 === 0 }"></span>
        <strong>00:00:01</strong>
      </div>
      <p class="story-note">每一次请求都有身份校验 · 每一次会话都可安全退出</p>
    </section>
    <main class="auth-panel">
      <div class="auth-card">
        <header class="auth-heading">
          <p>{{ eyebrow }}</p>
          <h2>{{ title }}</h2>
          <span>{{ subtitle }}</span>
        </header>
        <slot />
      </div>
    </main>
  </div>
</template>

<style>
.auth-stage{min-height:calc(100vh - 64px);display:grid;grid-template-columns:minmax(340px,44%) 1fr;background:#f4f6f8}.auth-story{position:relative;min-height:680px;padding:48px clamp(36px,6vw,88px);overflow:hidden;color:#f8fafc;background:radial-gradient(circle at 82% 12%,rgba(255,92,43,.24),transparent 28%),linear-gradient(145deg,#091522 0%,#10273a 65%,#152f43 100%)}.auth-story:after{content:"";position:absolute;width:420px;height:420px;border:1px solid rgba(255,255,255,.08);border-radius:50%;right:-210px;bottom:-160px;box-shadow:0 0 0 60px rgba(255,255,255,.025),0 0 0 120px rgba(255,255,255,.018)}.auth-brand{display:inline-flex;align-items:center;gap:10px;color:#fff;font-weight:800;font-size:20px;letter-spacing:.08em}.brand-bolt{display:grid;place-items:center;width:32px;height:32px;background:#ff5a2c;color:white;clip-path:polygon(20% 0,100% 0,72% 44%,96% 44%,25% 100%,42% 57%,8% 57%)}.auth-story-copy{position:relative;z-index:1;margin-top:clamp(100px,17vh,180px);max-width:510px}.story-kicker{color:#ff8a68;font:700 11px/1.2 ui-monospace,SFMono-Regular,Consolas,monospace;letter-spacing:.24em}.auth-story h1{margin:20px 0;font-family:"Arial Narrow","Microsoft YaHei",sans-serif;font-size:clamp(44px,5.6vw,82px);line-height:.98;letter-spacing:-.06em}.auth-story h1 em{color:#ff7045;font-style:normal}.auth-story-copy>p:last-child{max-width:430px;color:#afbeca;font-size:15px;line-height:1.8}.countdown-track{position:absolute;z-index:1;left:clamp(36px,6vw,88px);right:clamp(36px,6vw,88px);bottom:110px;height:62px;border-bottom:1px solid rgba(255,255,255,.2);display:flex;align-items:flex-end;justify-content:space-between}.countdown-track span{height:11px;width:1px;background:#738391}.countdown-track span.major{height:25px;background:#ff7045}.countdown-track strong{position:absolute;right:0;top:-22px;color:#fff;font:700 24px/1 ui-monospace,SFMono-Regular,Consolas,monospace;letter-spacing:.08em}.story-note{position:absolute;left:clamp(36px,6vw,88px);bottom:56px;color:#6f8392;font-size:11px;letter-spacing:.08em}.auth-panel{display:grid;place-items:center;padding:52px 24px}.auth-card{width:min(100%,480px)}.auth-heading{margin-bottom:30px}.auth-heading p{color:#e64a22;font:800 11px/1 ui-monospace,SFMono-Regular,Consolas,monospace;letter-spacing:.18em;text-transform:uppercase}.auth-heading h2{margin:12px 0 8px;font-size:32px;letter-spacing:-.04em;color:#102437}.auth-heading span{color:#71808d;font-size:14px}.auth-form{display:grid;gap:17px}.field{display:grid;gap:7px}.field label{color:#293d4d;font-size:13px;font-weight:700}.field input{width:100%;height:48px;padding:0 15px;border:1px solid #d9e0e5;border-radius:9px;background:#fff;color:#132635;font-size:14px;transition:border-color .2s,box-shadow .2s}.field input:focus{border-color:#ff6540;box-shadow:0 0 0 3px rgba(255,101,64,.12)}.field input.invalid{border-color:#d9363e}.field-error{color:#be2e35;font-size:12px}.inline-field{display:grid;grid-template-columns:1fr 132px;gap:10px}.captcha-image{width:132px;height:48px;object-fit:cover;border:1px solid #d9e0e5;border-radius:9px;cursor:pointer;background:white}.code-row{display:grid;grid-template-columns:1fr 126px;gap:10px}.send-code{border:1px solid #cbd5dc;border-radius:9px;background:#fff;color:#243a4c;font-weight:700}.send-code:hover:not(:disabled){border-color:#ff6540;color:#e64a22}.send-code:disabled{opacity:.55;cursor:not-allowed}.auth-submit{height:50px;margin-top:4px;border-radius:9px;background:#ff5b32;color:#fff;font-size:15px;font-weight:800;box-shadow:0 10px 24px rgba(255,91,50,.2);transition:transform .2s,background .2s}.auth-submit:hover:not(:disabled){background:#ed4821;transform:translateY(-1px)}.auth-submit:disabled{opacity:.58;cursor:not-allowed}.auth-alert{padding:12px 14px;border-left:3px solid #d9363e;background:#fff0f0;color:#a9232b;font-size:13px;line-height:1.5}.auth-success{border-left-color:#168a5b;background:#ebf8f2;color:#116944}.auth-links{display:flex;justify-content:space-between;gap:16px;margin-top:22px;color:#788690;font-size:13px}.auth-links a{color:#df4823;font-weight:700}.auth-tabs{display:grid;grid-template-columns:1fr 1fr;margin-bottom:24px;border-bottom:1px solid #dbe2e7}.auth-tabs button{padding:0 4px 12px;background:none;color:#7b8994;font-weight:700;border-bottom:2px solid transparent}.auth-tabs button.active{color:#142a3b;border-bottom-color:#ff5b32}@media(max-width:820px){.auth-stage{display:block}.auth-story{min-height:220px;padding:28px 24px}.auth-story-copy{margin-top:40px}.auth-story h1{font-size:42px}.auth-story-copy>p:last-child,.countdown-track,.story-note{display:none}.auth-panel{padding:36px 20px 56px}.auth-heading h2{font-size:28px}}@media(prefers-reduced-motion:reduce){.auth-submit,.field input{transition:none}}
</style>
