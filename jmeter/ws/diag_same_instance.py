"""绕过网关直连单个 order-service 实例，验证"同实例投递"是否正常。

对照组：走网关(8080)时，网关把连接负载均衡到 8104/8114 两个实例，
用户和客服大概率落在不同实例上，消息就要经 RabbitMQ 扇出——
而 chat.fanout 交换机没有任何绑定，扇出消息被静默丢弃。

直连时用 X-Internal-Token + X-User-Id 头（网关本来注入的就是这两个），
所以能精确控制两边落在哪个实例。
"""
import asyncio
import csv
import json
from pathlib import Path

import websockets

# 中文 Windows 上 Python 的 stdout 默认是 GBK，打印 ✓/→ 这类字符会抛
# UnicodeEncodeError 把脚本打断。这里降级成替换字符，保证不崩。
import sys as _sys
for _stream in (_sys.stdout, _sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(errors="replace")

HERE = Path(__file__).resolve().parent
INTERNAL_TOKEN = "local-dev-internal-token-change-in-production"
PORT = 8104


def load(name):
    with (HERE / name).open(newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def headers(user_id, role):
    return {"X-Internal-Token": INTERNAL_TOKEN, "X-User-Id": str(user_id),
            "X-User-Role": str(role), "X-User-Email": f"x{user_id}@t.local"}


async def recv_until(ws, wanted, timeout=15):
    while True:
        frame = json.loads(await asyncio.wait_for(ws.recv(), timeout))
        if frame.get("type") == wanted:
            return frame


async def main():
    user = load("chat-users.csv")[0]
    admin = load("chat-admins.csv")[0]
    url = f"ws://127.0.0.1:{PORT}/ws/chat"

    uws = await websockets.connect(url, additional_headers=headers(user["userId"], 0))
    await recv_until(uws, "connected")
    await uws.send(json.dumps({"type": "chat", "orderNo": user["orderNo"], "content": "预热 #warm"}))
    conv = (await recv_until(uws, "message"))["conversationId"]
    print(f"用户已连 8104，会话 id={conv}")

    aws = await websockets.connect(url, additional_headers=headers(admin["userId"], 1))
    await recv_until(aws, "connected")
    await aws.send(json.dumps({"type": "subscribe", "conversationId": conv}))
    print("客服已连 8104 并订阅")
    await asyncio.sleep(1)

    got_admin, got_user, hall = set(), set(), 0

    async def drain(ws, sink, tag, count_hall=False):
        nonlocal hall
        while True:
            try:
                frame = json.loads(await ws.recv())
            except Exception:
                return
            kind = frame.get("type")
            if kind == "message":
                sink.add(frame["message"]["content"].rpartition("#")[2].strip())
            elif kind == "conversation" and count_hall:
                hall += 1
            elif kind == "error":
                print(f"  [{tag}] 错误: {frame.get('message')}")

    ud = asyncio.create_task(drain(uws, got_user, "用户"))
    ad = asyncio.create_task(drain(aws, got_admin, "客服", count_hall=True))

    sent = []
    for i in range(10):
        m = f"s{i}"
        sent.append(m)
        await uws.send(json.dumps({"type": "chat", "orderNo": user["orderNo"], "content": f"压测 #{m}"}))
        await asyncio.sleep(0.8)

    await asyncio.sleep(3)
    ud.cancel(); ad.cancel()
    await uws.close(); await aws.close()

    print(f"\n发出 {len(sent)}")
    print(f"用户回显收到 {len(got_user)}   缺 {sorted(set(sent) - got_user)}")
    print(f"客服收到     {len(got_admin)}   缺 {sorted(set(sent) - got_admin)}")
    print(f"客服收到 conversation 大厅帧 {hall} 条（>0 说明 RabbitMQ 扇出是通的）")


asyncio.run(main())
