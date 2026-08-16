# AI Customer Service

This service is a controlled DeepSeek Agent for the seckill platform.

## Local run

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
Copy-Item .env.example .env
# set DEEPSEEK_API_KEY in .env
uvicorn app.main:app --reload --port 8200
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

The first version only supports FAQ, public product/activity lookup, and
read-only lookup of the current user's orders. It does not modify orders,
addresses, payments, stock, or shipment state.
