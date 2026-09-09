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

- 平台规则 FAQ —— 基于向量检索的 RAG（语义匹配，非关键词）
- 商品和秒杀活动只读查询
- 当前用户订单和履约状态只读查询
- Redis 会话记忆；Redis 不可用时本地开发自动退回内存
- 每次会话的 LLM 延迟、token 用量、工具调用链路可观测

当前不会自动修改地址、支付、取消订单、退款、库存或发货。生产环境应将 `DEEPSEEK_API_KEY` 和 `INTERNAL_TOKEN` 放在密钥管理系统中，不要写入前端或提交到仓库。

## RAG 检索（app/rag.py）

FAQ 检索用的是向量语义检索，而不是关键词匹配：

- **Embedding**：默认本地 `BAAI/bge-small-zh-v1.5`（sentence-transformers），也支持任意 OpenAI 兼容的 embedding API（`EMBEDDING_PROVIDER=openai`）。两者都不可用时退回一个零依赖的字符 n-gram 降级实现，保证服务不崩。
- **向量库**：FAISS `IndexFlatIP`（向量归一化后内积即余弦相似度）；未安装 FAISS 时自动退回 NumPy 余弦检索。
- **索引缓存**：embedding 按「知识库内容 + embedder」哈希缓存到 `INDEX_CACHE_DIR`，内容不变则不重复计算。
- **知识库**：`app/faq.json`，每条含 `id / category / question / answer / keywords`。
- `search_faq` 取 Top-K 并用 `RAG_MIN_SCORE` 阈值过滤，低于阈值返回“未匹配”，避免模型编造规则。

本地启用真实语义 embedding：

```powershell
pip install -r requirements-embeddings.txt   # 安装 sentence-transformers
# 首次运行会自动从 hf-mirror.com 下载 bge 模型（约 95MB）
```

## 评测（eval/）

无需改代码就能量化检索与回答质量：

```powershell
# 检索评测（默认，无需调用大模型）：recall@1 / recall@k / MRR / 拒答准确率
python eval/run_eval.py --report eval/report.json

# 端到端评测（需 DEEPSEEK_API_KEY）：工具路由准确率 / 答案关键词覆盖 / 延迟 / token
python eval/run_eval.py --mode e2e
```

数据集 `eval/dataset.jsonl` 用口语化提问（与 FAQ 原文不同措辞）来真实检验语义检索，并含一条无关问题检验拒答。

## 可观测性（app/observability.py）

- 自定义 `MetricsCallbackHandler`（LangChain 回调）汇总每次请求的 LLM 延迟、token 用量、每个工具的调用名/耗时/成败，输出一行结构化 JSON 日志。
- 可选 LangSmith 全链路追踪：在 `.env` 设 `LANGCHAIN_TRACING_V2=true` 和 `LANGCHAIN_API_KEY` 即自动接入。

## 面试讲解点

- Agent 架构：LangGraph 状态图 + 工具节点 + 条件边构成 agent-tool 循环，而非裸调 API。
- RAG：本地 bge 向量化 + FAISS 检索 + 阈值拒答 + 索引缓存，embedding 后端可插拔。
- 工程化：可量化的评测（recall/MRR/工具路由）+ 自定义回调可观测 + LangSmith。
- 安全：网关注入身份、内部令牌鉴权、严格用户数据隔离、全部只读工具、提示词防泄露。
