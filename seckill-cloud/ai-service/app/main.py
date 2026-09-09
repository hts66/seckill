import json
import time
import uuid
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, StreamingResponse

from .agent import build_graph, initial_messages
from .config import Settings, get_settings
from .models import ApiResponse, ChatRequest, ChatResponse, ConversationCreateResponse, HealthResponse, UserContext
from .observability import MetricsCallbackHandler, log_request, setup_observability
from .rag import get_rag_engine
from .store import ConversationStore, StoredMessage


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    setup_observability(settings)
    get_rag_engine()  # warm embeddings + build the vector index at startup
    app.state.store = ConversationStore(settings)
    yield
    await app.state.store.close()


app = FastAPI(title="Seckill AI Customer Service", version="0.1.0", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["GET", "POST"], allow_headers=["*"])


@app.exception_handler(HTTPException)
async def http_exception_handler(_request: Request, exc: HTTPException):
    message = exc.detail if isinstance(exc.detail, str) else "request failed"
    return JSONResponse(
        status_code=exc.status_code,
        content={"code": exc.status_code, "message": message, "data": None},
        headers=exc.headers,
    )


def current_user(
    x_internal_token: str | None = Header(default=None),
    x_user_id: str | None = Header(default=None),
    x_user_email: str | None = Header(default=None),
    x_user_role: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> UserContext:
    if x_internal_token != settings.internal_token:
        raise HTTPException(status_code=403, detail="internal access required")
    try:
        return UserContext(user_id=int(x_user_id or ""), email=x_user_email, role=int(x_user_role or "0"))
    except ValueError as exc:
        raise HTTPException(status_code=401, detail="authenticated user required") from exc


@app.get("/health", response_model=HealthResponse)
async def health(settings: Settings = Depends(get_settings)):
    return HealthResponse(model_configured=bool(settings.deepseek_api_key))


@app.post("/api/customer-service/conversations", response_model=ApiResponse[ConversationCreateResponse])
async def create_conversation(request: Request, user: UserContext = Depends(current_user)):
    conversation_id = await request.app.state.store.create(user.user_id)
    return ApiResponse(data=ConversationCreateResponse(conversation_id=conversation_id))


async def load_context(request: Request, conversation_id: str, user: UserContext, message: str):
    history = await request.app.state.store.load(conversation_id, user.user_id)
    graph, tools = build_graph(get_settings(), user)
    messages = initial_messages([(item.role, item.content) for item in history], message)
    return graph, tools, messages


@app.post("/api/customer-service/conversations/{conversation_id}/messages", response_model=ApiResponse[ChatResponse])
async def chat(conversation_id: str, body: ChatRequest, request: Request, user: UserContext = Depends(current_user)):
    handler = MetricsCallbackHandler()
    started = time.perf_counter()
    ok, error_msg = True, None
    try:
        graph, tools, messages = await load_context(request, conversation_id, user, body.message)
        result = await graph.ainvoke({"messages": messages}, config={"callbacks": [handler]})
        answer = str(result["messages"][-1].content)
        await request.app.state.store.append(conversation_id, user.user_id, [StoredMessage("user", body.message), StoredMessage("assistant", answer)])
        return ApiResponse(
            data=ChatResponse(conversation_id=conversation_id, message_id=str(uuid.uuid4()), answer=answer)
        )
    except RuntimeError as exc:
        ok, error_msg = False, str(exc)
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except Exception as exc:
        ok, error_msg = False, str(exc)
        raise HTTPException(status_code=502, detail=f"客服暂时不可用：{exc}") from exc
    finally:
        if "tools" in locals():
            await tools.close()
        log_request(conversation_id=conversation_id, user_id=user.user_id, mode="sync",
                    handler=handler, total_ms=(time.perf_counter() - started) * 1000, ok=ok, error=error_msg)


@app.post("/api/customer-service/conversations/{conversation_id}/messages/stream")
async def stream_chat(conversation_id: str, body: ChatRequest, request: Request, user: UserContext = Depends(current_user)):
    handler = MetricsCallbackHandler()
    started = time.perf_counter()
    try:
        graph, tools, messages = await load_context(request, conversation_id, user, body.message)
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc

    async def events():
        chunks: list[str] = []
        ok, error_msg = True, None
        try:
            async for event in graph.astream_events({"messages": messages}, version="v2", config={"callbacks": [handler]}):
                if await request.is_disconnected():
                    break
                if event.get("event") != "on_chat_model_stream":
                    continue
                chunk = event.get("data", {}).get("chunk")
                content = getattr(chunk, "content", "") if chunk is not None else ""
                if isinstance(content, list):
                    content = "".join(part.get("text", "") for part in content if isinstance(part, dict))
                if content:
                    chunks.append(content)
                    yield f"data: {json.dumps({'type': 'token', 'content': content}, ensure_ascii=False)}\n\n"
            answer = "".join(chunks).strip()
            if answer:
                await request.app.state.store.append(conversation_id, user.user_id, [StoredMessage("user", body.message), StoredMessage("assistant", answer)])
            yield f"data: {json.dumps({'type': 'done', 'messageId': str(uuid.uuid4())}, ensure_ascii=False)}\n\n"
        except Exception as exc:
            ok, error_msg = False, str(exc)
            yield f"data: {json.dumps({'type': 'error', 'message': f'客服暂时不可用：{exc}'}, ensure_ascii=False)}\n\n"
        finally:
            await tools.close()
            log_request(conversation_id=conversation_id, user_id=user.user_id, mode="stream",
                        handler=handler, total_ms=(time.perf_counter() - started) * 1000, ok=ok, error=error_msg)

    return StreamingResponse(events(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
