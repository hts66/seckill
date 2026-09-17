"""WS 聊天链路冒烟：铸 token -> 连网关 -> 发消息 -> 收回显。"""
import asyncio
import base64
import json
import time
from pathlib import Path

import pymysql
import websockets
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

# 中文 Windows 上 Python 的 stdout 默认是 GBK，打印 ✓/→ 这类字符会抛
# UnicodeEncodeError 把脚本打断。这里降级成替换字符，保证不崩。
import sys as _sys
for _stream in (_sys.stdout, _sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(errors="replace")

PRIVATE_KEY = Path(r"C:\Users\27036\Desktop\seckill\seckill-cloud\keys\private.pem")


def b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def make_token(private_key, subject: int, email: str, ttl: int = 7200) -> str:
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    payload = {"sub": str(subject), "email": email, "role": 0, "tokenVersion": 0,
               "type": "access", "iat": now, "exp": now + ttl}
    eh = b64url(json.dumps(header, separators=(",", ":")).encode())
    ep = b64url(json.dumps(payload, separators=(",", ":")).encode())
    sig = private_key.sign(f"{eh}.{ep}".encode(), padding.PKCS1v15(), hashes.SHA256())
    return f"{eh}.{ep}.{b64url(sig)}"


def pick_order():
    conn = pymysql.connect(host="127.0.0.1", port=3307, user="seckill",
                           password="seckill123", charset="utf8mb4")
    with conn.cursor() as cur:
        cur.execute("SELECT user_id, order_no FROM seckill_order.seckill_orders "
                    "WHERE user_id <> 3 ORDER BY user_id LIMIT 1")
        return cur.fetchone()


async def main():
    user_id, order_no = pick_order()
    key = serialization.load_pem_private_key(PRIVATE_KEY.read_bytes(), password=None)
    token = make_token(key, user_id, f"load{user_id}@test.com")
    url = f"ws://127.0.0.1:8080/ws/chat?token={token}"
    print(f"user={user_id} order={order_no}")

    t0 = time.perf_counter()
    async with websockets.connect(url, max_size=None) as ws:
        connected = json.loads(await asyncio.wait_for(ws.recv(), 10))
        print(f"握手+connected: {(time.perf_counter()-t0)*1000:.0f}ms  {connected}")

        # ping/pong
        t1 = time.perf_counter()
        await ws.send(json.dumps({"type": "ping"}))
        pong = json.loads(await asyncio.wait_for(ws.recv(), 10))
        print(f"ping->pong: {(time.perf_counter()-t1)*1000:.0f}ms  {pong}")

        # 发消息，收回显
        t2 = time.perf_counter()
        await ws.send(json.dumps({"type": "chat", "orderNo": order_no, "content": "压测冒烟"}))
        while True:
            frame = json.loads(await asyncio.wait_for(ws.recv(), 15))
            print(f"  <- {frame.get('type')}  {(time.perf_counter()-t2)*1000:.0f}ms")
            if frame.get("type") == "message":
                print(f"投递延迟: {(time.perf_counter()-t2)*1000:.1f}ms")
                print(f"  内容: {frame['message']['content']}  会话: {frame['message']['conversationId']}")
                break
            if frame.get("type") == "error":
                print("  错误:", frame.get("message"))
                break


asyncio.run(main())
