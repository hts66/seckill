"""诊断：1 用户 + 1 客服，逐条打印用户发出的消息客服是否收到。"""
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


def load(name):
    with (HERE / name).open(newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


async def recv_until(ws, wanted, timeout=15):
    while True:
        frame = json.loads(await asyncio.wait_for(ws.recv(), timeout))
        if frame.get("type") == wanted:
            return frame


async def main():
    user = load("chat-users.csv")[0]
    admin = load("chat-admins.csv")[0]
    base = "ws://127.0.0.1:8080/ws/chat"

    uws = await websockets.connect(f"{base}?token={user['token']}")
    print("用户 connected:", await recv_until(uws, "connected"))
    await uws.send(json.dumps({"type": "chat", "orderNo": user["orderNo"], "content": "预热 #warm"}))
    warm = await recv_until(uws, "message")
    conv = warm["conversationId"]
    print(f"会话 id = {conv}")

    aws = await websockets.connect(f"{base}?token={admin['token']}")
    print("客服 connected:", await recv_until(aws, "connected"))
    await aws.send(json.dumps({"type": "subscribe", "conversationId": conv}))
    await asyncio.sleep(1.5)

    received_by_admin = set()
    received_by_user = set()

    async def drain(ws, sink, tag):
        while True:
            try:
                frame = json.loads(await ws.recv())
            except Exception as e:
                print(f"  [{tag}] 连接异常: {type(e).__name__}")
                return
            kind = frame.get("type")
            if kind == "message":
                marker = frame["message"]["content"].rpartition("#")[2].strip()
                sink.add(marker)
                print(f"  [{tag}] <- message {marker}")
            elif kind == "error":
                print(f"  [{tag}] 错误: {frame.get('message')}")
            else:
                # conversation = 客服大厅的会话快照，经 RabbitMQ fanout 送达。
                # 收不到它说明扇出链路断了，收到它却没有 message 说明房间没进去。
                print(f"  [{tag}] <- {kind}")

    ud = asyncio.create_task(drain(uws, received_by_user, "用户"))
    ad = asyncio.create_task(drain(aws, received_by_admin, "客服"))

    sent = []
    for i in range(12):
        marker = f"d{i}"
        sent.append(marker)
        await uws.send(json.dumps({"type": "chat", "orderNo": user["orderNo"], "content": f"压测 #{marker}"}))
        print(f"[发] {marker}")
        await asyncio.sleep(1)

    await asyncio.sleep(3)
    ud.cancel(); ad.cancel()
    await uws.close(); await aws.close()

    print(f"\n发出 {len(sent)}")
    print(f"用户回显收到 {len(received_by_user)}  缺 {sorted(set(sent) - received_by_user)}")
    print(f"客服收到     {len(received_by_admin)}  缺 {sorted(set(sent) - received_by_admin)}")


asyncio.run(main())
