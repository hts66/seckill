# 秒杀商城前端

这是秒杀商城的 Vue 3 前端，包含用户登录、商品浏览、秒杀倒计时、订单、收货地址、管理员后台和 AI 智能客服入口。

## 启动

```powershell
cd C:\Users\27036\Desktop\seckill\seckill-frontend
npm install
npm run dev
```

开发环境默认通过 Vite 代理访问微服务网关 `http://localhost:8080`。如果临时运行根目录的单体后端：

```powershell
$env:VITE_API_TARGET = 'http://localhost:8081'
npm run dev
```

## 构建

```powershell
npm run build
npm run preview
```

微服务版 Docker Compose 会使用 Nginx 托管构建产物，前端请求统一转发到 Gateway。完整的环境变量和后端启动说明见项目根目录 [`README.md`](../README.md)。
