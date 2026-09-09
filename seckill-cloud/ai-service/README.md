# AI Customer Service

This service is a controlled DeepSeek Agent for the seckill platform.

## Local run

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
pip install -r requirements-embeddings.txt   # optional: local bge embeddings for RAG
Copy-Item .env.example .env
# set DEEPSEEK_API_KEY in .env
uvicorn app.main:app --reload --port 8200
```

FAQ answers are served by semantic RAG (see `app/rag.py`): local
`BAAI/bge-small-zh-v1.5` embeddings by default, or any OpenAI-compatible
embedding API via `EMBEDDING_PROVIDER=openai`, indexed with FAISS (NumPy
fallback). Without `requirements-embeddings.txt` and without an embedding API,
retrieval degrades to a lexical fallback so the service still runs.

## Evaluation

```powershell
python eval/run_eval.py                    # retrieval: recall@1 / recall@k / MRR / no-match
python eval/run_eval.py --mode e2e         # end-to-end: tool routing / answer coverage / latency
```

The service expects the gateway to provide `X-Internal-Token`, `X-User-Id`,
`X-User-Email`, and `X-User-Role`. It never accepts a user id from the chat
request body. Business tools call the Java services with the same internal
token and the authenticated user headers.

## API

- `POST /api/customer-service/conversations`
- `POST /api/customer-service/conversations/{id}/messages`
- `POST /api/customer-service/conversations/{id}/messages/stream`
- `GET /health`

This service supports semantic FAQ retrieval (RAG), public product/activity
lookup, and read-only lookup of the current user's orders. It does not modify
orders, addresses, payments, stock, or shipment state. Every request emits a
structured metrics log (LLM latency, tokens, tool-call chain) via a custom
LangChain callback handler in `app/observability.py`; optional LangSmith tracing
turns on with `LANGCHAIN_TRACING_V2=true`.
