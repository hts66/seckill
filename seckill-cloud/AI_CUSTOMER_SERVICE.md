# DeepSeek 智能客服

客服服务位于 `ai-service`，技术栈为 FastAPI、LangChain、LangGraph 和 DeepSeek OpenAI 兼容 API。它通过 Gateway 认证后使用 `X-User-Id` 查询当前用户自己的订单，模型不能直接访问数据库，也没有订单写操作工具。

## 启动

在 `seckill-cloud` 目录设置 DeepSeek Key：

```powershell
$env:DEEPSEEK_API_KEY = '你的 DeepSeek API Key'
$env:INTERNAL_TOKEN = '与 Java 服务相同的内部令牌'
docker compose up -d ai-service
```

Gateway 使用 `AI_SERVICE_URL` 连接客服服务，默认地址为 `http://localhost:8200`。完整的 Java 服务启动后，前端登录用户即可看到右下角客服浮窗。

## 接口

- `POST /api/customer-service/conversations` 创建会话
- `POST /api/customer-service/conversations/{id}/messages` 普通回复
- `POST /api/customer-service/conversations/{id}/messages/stream` SSE 流式回复
- `GET http://localhost:8200/health` 服务健康检查

## 当前能力

- 平台规则 FAQ
- 商品和秒杀活动只读查询
- 当前用户订单和履约状态只读查询
- Redis 会话记忆；Redis 不可用时本地开发自动退回内存

当前不会自动修改地址、支付、取消订单、退款、库存或发货。生产环境应将 `DEEPSEEK_API_KEY` 和 `INTERNAL_TOKEN` 放在密钥管理系统中，不要写入前端或提交到仓库。
