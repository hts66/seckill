"""订单客服实时聊天（WebSocket /ws/chat）压测器。

为什么用 asyncio 而不是 JMeter
------------------------------
1. 本机 JMeter 没装 WebSocket 采样器插件；即便装上，JMeter 一个线程一条连接，
   压到千级连接要上分布式，而 asyncio 单进程就能扛几千条。
2. "用户发消息 -> 客服收到"这条端到端链路要跨两条连接做时间戳配对，
   JMeter 里靠多线程关联非常别扭。
3. 逐帧到达时间戳（握手、回显、投递分别耗时）JMeter 给不了。

服务端硬约束（写死在这套代码里了，压测必须绕着走）
--------------------------------------------------
* 限流：单用户 5 条/秒（固定窗口），超了收到 {"type":"error"} —— 所以 --rate 默认 1，
  超过 5 会直接告警。
* 单账号最多 4 条连接，所以虚拟用户之间**不能共用 userId**。
* 90s 无活动会被踢，每 30s 扫一次 —— 所以 --hold 场景要发心跳。

三种场景
--------
connect  只建连并保持，测握手耗时、连接成功率、连接能撑住多少
echo     N 个用户按速率发言，测「发出 -> 自己收到」的往返（服务端处理 + 房间广播）
relay    N 个用户 + M 个客服，测「用户发出 -> 客服收到」的端到端投递延迟

    python ws_chat_load.py --scenario connect --users 500 --ramp 20 --hold 60
    python ws_chat_load.py --scenario echo    --users 100 --rate 1 --duration 60
    python ws_chat_load.py --scenario relay   --users 50 --admins 2 --rate 1 --duration 60
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import json
import random
import time
from collections import Counter
from pathlib import Path

import websockets

# 中文 Windows 上 Python 的 stdout 默认是 GBK，打印 ✓/→ 这类字符会抛
# UnicodeEncodeError 把脚本打断。这里降级成替换字符，保证不崩。
import sys as _sys
for _stream in (_sys.stdout, _sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(errors="replace")

HERE = Path(__file__).resolve().parent
SERVER_RATE_LIMIT = 5          # app.chat.message-rate-per-second
SERVER_MAX_CONN_PER_USER = 4   # app.chat.max-connections-per-user


class Results:
    def __init__(self) -> None:
        self.handshake: list[float] = []
        self.echo: list[float] = []
        self.relay: list[float] = []
        self.errors: Counter = Counter()
        self.connect_failed = 0
        self.messages_sent = 0
        self.messages_received = 0
        self.disconnects = 0
        # 背景连接压力：只挂着收消息、不发消息，用来复现"高并发连接下"的投递
        self.idle_connected = 0
        self.idle_failed = 0
        self.idle_disconnects = 0
        # marker -> 发送时刻（user 与 admin 协程共享，用于算端到端延迟）
        self.pending: dict[str, float] = {}
        self.relay_seen: set[str] = set()

    def fail(self, label: str) -> None:
        self.errors[label] += 1


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = max(1, min(len(ordered), int(round(len(ordered) * pct / 100 + 0.5))))
    return ordered[rank - 1]


def load_csv(path: Path, need_order: bool) -> list[dict]:
    with path.open(newline="", encoding="utf-8-sig") as handle:
        rows = [r for r in csv.DictReader(handle) if r.get("token")]
    if not rows:
        raise SystemExit(f"{path} 里没有可用用户，先跑 prepare_chat_users.py")
    if need_order and not rows[0].get("orderNo"):
        raise SystemExit(f"{path} 缺少 orderNo 列")
    return rows


def ws_url(host: str, port: int, token: str) -> str:
    return f"ws://{host}:{port}/ws/chat?token={token}"


def instance_headers(user: dict, role: int, token: str) -> dict:
    """直连实例时用：网关本来注入的就是这几个头，绕过网关就得自己带。

    这是精确控制"连接落在哪个实例"的唯一办法——经网关时由 LoadBalancer 决定，
    客户端无从得知，只能靠延迟阈值去猜。
    """
    return {
        "X-Internal-Token": token,
        "X-User-Id": str(user["userId"]),
        "X-User-Role": str(role),
        "X-User-Email": user.get("email") or f"u{user['userId']}@t.local",
    }


async def open_chat(base: str, token: str, results: Results, headers: dict | None = None):
    """建连并等到 connected 帧；返回 (websocket, 握手毫秒)。失败抛异常。"""
    started = time.perf_counter()
    ws = await websockets.connect(
        ws_url(*base, token),
        additional_headers=headers,
        max_size=2 ** 20,
        open_timeout=15,
        close_timeout=3,
        ping_interval=None,   # 关掉库自带的心跳，用应用层的 {"type":"ping"}
        ping_timeout=None,
    )
    try:
        raw = await asyncio.wait_for(ws.recv(), 15)
        frame = json.loads(raw)
        if frame.get("type") != "connected":
            raise RuntimeError(f"期望 connected，收到 {frame.get('type')}")
    except Exception:
        await ws.close()
        raise
    return ws, (time.perf_counter() - started) * 1000


async def consume(ws, results: Results, admin: bool, stop: asyncio.Event) -> None:
    """后台读取协程：把服务端帧归到 echo / relay 统计里。"""
    while not stop.is_set():
        try:
            raw = await ws.recv()
        except Exception:
            results.disconnects += 1
            return
        try:
            frame = json.loads(raw)
        except Exception:
            continue
        kind = frame.get("type")
        if kind == "error":
            results.fail(f"server:{frame.get('message')}")
            continue
        if kind == "pong":
            continue
        if kind == "message":
            content = (frame.get("message") or {}).get("content") or ""
            marker = content.rpartition("#")[2].strip()
            if not marker:
                continue
            if admin:
                if marker in results.relay_seen:
                    continue
                results.relay_seen.add(marker)
                sent_at = results.pending.get(marker)
                if sent_at is not None:
                    results.relay.append((time.perf_counter() - sent_at) * 1000)
                    results.messages_received += 1
            else:
                sent_at = results.pending.get(marker)
                if sent_at is not None:
                    results.echo.append((time.perf_counter() - sent_at) * 1000)
                    results.messages_received += 1


async def heartbeat(ws, stop: asyncio.Event) -> None:
    """保活：服务端 90s 无活动就踢连接，这里 30s 发一次 ping。"""
    while not stop.is_set():
        try:
            await asyncio.wait_for(stop.wait(), timeout=30)
            return
        except asyncio.TimeoutError:
            pass
        try:
            await ws.send('{"type":"ping"}')
        except Exception:
            return


async def idle_holder(base, user, results, stop, headers=None):
    """背景连接：只建连 + 保活 + 收帧，从不发言。

    用来制造"高并发连接数"这个压力条件——服务端每条连接要占一个 session、
    一个房间集合、一个 decorator 和一个 lastActive 表项，连接数本身就是负载。
    """
    try:
        ws, hs = await open_chat(base, user["token"], results, headers)
    except Exception as exc:
        results.idle_failed += 1
        results.fail(f"idle-connect:{type(exc).__name__}")
        return
    results.idle_connected += 1
    results.handshake.append(hs)
    hb = asyncio.create_task(heartbeat(ws, stop))
    consumer = asyncio.create_task(consume(ws, results, False, stop))
    try:
        await stop.wait()
    finally:
        hb.cancel()
        consumer.cancel()
        try:
            await ws.close()
        except Exception:
            pass


async def user_connect_only(base, user, results, args, stop, started_at):
    try:
        ws, hs = await open_chat(base, user["token"], results)
    except Exception as exc:
        results.connect_failed += 1
        results.fail(f"connect:{type(exc).__name__}")
        return
    results.handshake.append(hs)
    hb = asyncio.create_task(heartbeat(ws, stop))
    consume_task = asyncio.create_task(consume(ws, results, False, stop))
    try:
        await stop.wait()
    finally:
        hb.cancel()
        consume_task.cancel()
        await ws.close()


async def user_chat(base, user, index, results, args, stop, admin_convs=None, headers=None):
    """连上 -> 预热建会话（relay 需要）-> 按速率发言。"""
    try:
        ws, hs = await open_chat(base, user["token"], results, headers)
    except Exception as exc:
        results.connect_failed += 1
        results.fail(f"connect:{type(exc).__name__}")
        return
    results.handshake.append(hs)
    interval = 1.0 / args.rate if args.rate > 0 else 0
    seq = 0
    consumer = None
    try:
        if admin_convs is not None:
            # 预热：第一条消息会创建会话，回帧里带回 conversationId。
            # 必须在启动 consume 协程之前做——两个协程同时 recv 同一条连接会互相抢帧。
            marker = f"warm{index}"
            await ws.send(json.dumps(
                {"type": "chat", "orderNo": user["orderNo"], "content": f"预热 #{marker}"}, ensure_ascii=False))
            try:
                while True:
                    raw = await asyncio.wait_for(ws.recv(), 20)
                    frame = json.loads(raw)
                    if frame.get("type") == "message":
                        admin_convs.append(frame.get("conversationId"))
                        break
                    if frame.get("type") == "error":
                        results.fail(f"warmup:{frame.get('message')}")
                        return
            except Exception as exc:
                results.fail(f"warmup:{type(exc).__name__}")
                return
            # 预热帧不参与统计（这条谁都没订阅，客服收不到）
            results.echo.clear()

        consumer = asyncio.create_task(consume(ws, results, False, stop))
        send_deadline = time.perf_counter() + args.duration
        while not stop.is_set() and time.perf_counter() < send_deadline:
            seq += 1
            marker = f"u{index}s{seq}"
            results.pending[marker] = time.perf_counter()
            payload = json.dumps(
                {"type": "chat", "orderNo": user["orderNo"], "content": f"压测 #{marker}"}, ensure_ascii=False)
            try:
                await ws.send(payload)
                results.messages_sent += 1
            except Exception as exc:
                results.fail(f"send:{type(exc).__name__}")
                results.pending.pop(marker, None)
                break
            # 防止丢帧导致 pending 无限增长
            if len(results.pending) > 20000:
                for key in list(results.pending)[:10000]:
                    results.pending.pop(key, None)
            await asyncio.sleep(interval * random.uniform(0.85, 1.15))
    finally:
        consumer.cancel()
        try:
            await ws.close()
        except Exception:
            pass


async def admin_subscriber(base, admin, convs, results, stop, ready: asyncio.Event, headers=None):
    try:
        ws, hs = await open_chat(base, admin["token"], results, headers)
    except Exception as exc:
        results.connect_failed += 1
        results.fail(f"admin-connect:{type(exc).__name__}")
        ready.set()
        return
    consume_task = asyncio.create_task(consume(ws, results, True, stop))
    try:
        for conv_id in convs:
            await ws.send(json.dumps({"type": "subscribe", "conversationId": conv_id}))
        ready.set()
        hb = asyncio.create_task(heartbeat(ws, stop))
        await stop.wait()
        hb.cancel()
    finally:
        consume_task.cancel()
        try:
            await ws.close()
        except Exception:
            pass


async def run(args) -> Results:
    results = Results()
    base = (args.host, args.port)
    all_users = load_csv(Path(args.users_file), need_order=args.scenario in ("echo", "relay"))
    users = all_users[: args.users]
    if len(users) < args.users:
        raise SystemExit(f"用户文件只有 {len(all_users)} 个，不够 {args.users} 个并发用户")

    stop = asyncio.Event()
    started_at = time.perf_counter()
    tasks = []

    if args.scenario == "connect":
        # 一个用户可开多条连接（服务端上限 4），用于把连接总数压到虚拟用户数以上。
        # 只要不超过 4，就不会触发"连接数过多"的拒绝。
        connections = [u for _ in range(args.conns_per_user) for u in users]
        if args.conns_per_user > SERVER_MAX_CONN_PER_USER:
            print(f"[警告] --conns-per-user {args.conns_per_user} 超过服务端单账号上限 "
                  f"{SERVER_MAX_CONN_PER_USER}，超出部分会被拒")
        for user in connections:
            if args.ramp:
                await asyncio.sleep(args.ramp / len(connections))
            tasks.append(asyncio.create_task(
                user_connect_only(base, user, results, args, stop, started_at)))
        await asyncio.sleep(args.hold)
        stop.set()
        await asyncio.gather(*tasks, return_exceptions=True)
        return results

    if args.scenario == "echo":
        for index, user in enumerate(users):
            if args.ramp:
                await asyncio.sleep(args.ramp / len(users))
            tasks.append(asyncio.create_task(
                user_chat(base, user, index, results, args, stop)))
        await asyncio.gather(*tasks, return_exceptions=True)
        return results

    # ---- relay：先让用户建好会话，再让客服订阅，然后才开始计时 ----
    #
    # 同实例 / 跨实例由连接落在哪个实例决定：
    #   gateway 模式 —— 由网关 LoadBalancer 决定，客户端不知道，只能按延迟阈值猜；
    #   direct  模式 —— 自己指定端口，分类是**真值**，才是严谨的对照实验。
    direct = args.mode == "direct"
    sender_base = (args.host, args.sender_port) if direct else base
    admin_base = (args.host, args.admin_port) if direct else base
    idle_base = (args.host, args.idle_port) if direct else base

    def u_headers(user):
        return instance_headers(user, 0, args.internal_token) if direct else None

    def a_headers(admin):
        return instance_headers(admin, 1, args.internal_token) if direct else None

    convs: list[int] = []
    warm_tasks = [asyncio.create_task(
        user_chat(sender_base, u, i, results, args, stop, admin_convs=convs, headers=u_headers(u)))
        for i, u in enumerate(users)]
    # 等所有用户都拿到 conversationId（或超时）
    for _ in range(200):
        if len(convs) >= len(users):
            break
        await asyncio.sleep(0.1)
    stop.set()
    await asyncio.gather(*warm_tasks, return_exceptions=True)
    if not convs:
        raise SystemExit("预热阶段没有建出任何会话，检查订单号是否有效")

    print(f"预热完成：{len(convs)}/{len(users)} 个会话")

    # 重置统计，把预热期的数据丢掉
    results.handshake.clear()
    results.errors.clear()
    results.disconnects = 0

    stop = asyncio.Event()
    started_at = time.perf_counter()
    idle_tasks = []

    # 背景连接压力：在测量开始前就把它们挂上去，保持稳态
    if args.idle:
        # 关键：空闲连接要用**没被发言占用**的用户，否则会把发言用户的 4 条连接额度吃满，
        # 导致发言连接被服务端以"连接数过多"拒绝，整个测量作废。
        pool = all_users[args.users:] or all_users
        idle_plan = []
        for i in range(args.idle):
            slot = i // len(pool)
            if slot >= SERVER_MAX_CONN_PER_USER:
                print(f"[警告] --idle {args.idle} 需要每用户 {slot + 1} 条连接，"
                      f"超过上限 {SERVER_MAX_CONN_PER_USER}；"
                      f"当前用户池 {len(pool)} 个，实际最多 {len(pool) * SERVER_MAX_CONN_PER_USER} 条")
                break
            idle_plan.append(pool[i % len(pool)])
        print(f"建立 {len(idle_plan)} 条背景空闲连接（用户池 {len(pool)} 个，"
              f"与发言用户不重叠）…")
        for user in idle_plan:
            if args.ramp:
                await asyncio.sleep(args.ramp / max(1, len(idle_plan)))
            idle_tasks.append(asyncio.create_task(
                idle_holder(idle_base, user, results, stop, u_headers(user))))

    ready = asyncio.Event()
    admins = load_csv(Path(args.admins_file), need_order=False)[: args.admins]
    admin_tasks = []
    for i, admin in enumerate(admins):
        share = convs[i::len(admins)]
        admin_tasks.append(asyncio.create_task(
            admin_subscriber(admin_base, admin, share, results, stop, ready, a_headers(admin))))
    await ready.wait()
    await asyncio.sleep(2)  # 等 subscribe 帧被服务端处理完

    for index, user in enumerate(users):
        if args.ramp:
            await asyncio.sleep(args.ramp / len(users))
        tasks.append(asyncio.create_task(
            user_chat(sender_base, user, index, results, args, stop, headers=u_headers(user))))
    await asyncio.gather(*tasks, return_exceptions=True)
    stop.set()
    await asyncio.gather(*admin_tasks, *idle_tasks, return_exceptions=True)
    return results


def report(results: Results, args, elapsed: float) -> None:
    print(f"\n{'=' * 62}")
    print(f"场景 {args.scenario}   耗时 {elapsed:.1f}s")
    print(f"{'=' * 62}")
    print(f"建连成功 {len(results.handshake)}   失败 {results.connect_failed}   "
          f"中途掉线 {results.disconnects}")
    if args.idle:
        print(f"背景空闲连接 {results.idle_connected}/{args.idle}（失败 {results.idle_failed}）"
              f"  ← 测量期间的并发连接压力")
    print(f"发出 {results.messages_sent} 条   收到 {results.messages_received} 条")

    if results.handshake:
        h = results.handshake
        print(f"\n握手耗时   p50={percentile(h, 50):.0f}ms  p90={percentile(h, 90):.0f}ms  "
              f"p95={percentile(h, 95):.0f}ms  p99={percentile(h, 99):.0f}ms  max={max(h):.0f}ms")
    if results.echo:
        e = results.echo
        print(f"回显延迟   p50={percentile(e, 50):.1f}ms  p90={percentile(e, 90):.1f}ms  "
              f"p95={percentile(e, 95):.1f}ms  p99={percentile(e, 99):.1f}ms  max={max(e):.0f}ms")
    if results.relay:
        r = results.relay
        print(f"投递延迟   p50={percentile(r, 50):.1f}ms  p90={percentile(r, 90):.1f}ms  "
              f"p95={percentile(r, 95):.1f}ms  p99={percentile(r, 99):.1f}ms  max={max(r):.0f}ms")
        # 双峰拆分：同实例走本地房间广播（毫秒级），跨实例要等落库 flush + MQ 扇出（百毫秒级）。
        # 不拆开看的话，p50 会被快的那些拉低，掩盖掉慢的那一半。
        fast = [x for x in r if x < args.split_ms]
        slow = [x for x in r if x >= args.split_ms]
        total = len(r) or 1
        if args.mode == "direct":
            same = args.sender_port == args.admin_port
            head = "同实例" if same else "跨实例"
            print(f"  [{args.mode}] 发言连 {args.sender_port} / 客服连 {args.admin_port} "
                  f"→ 该链路是**{head}**（端口确定，非推断）")
        print(f"  ├ <{args.split_ms:.0f}ms     {len(fast):>5} 条 {len(fast)/total*100:5.1f}%  "
              f"p50={percentile(fast, 50):.1f}ms  p95={percentile(fast, 95):.1f}ms")
        print(f"  └ >={args.split_ms:.0f}ms    {len(slow):>5} 条 {len(slow)/total*100:5.1f}%  "
              f"p50={percentile(slow, 50):.1f}ms  p95={percentile(slow, 95):.1f}ms")
        print(f"     注意：这个阈值分组只是**分布展示**，不能当同/跨实例的判据——"
              f"跨实例消息若刚好赶在 flush 前到达，同样能落在 {args.split_ms:.0f}ms 以内。")
        if args.mode == "gateway":
            print(f"     gateway 模式要分同/跨实例做对照实验，请改用 --mode direct 固定端口。")
    if results.messages_sent and results.echo:
        print(f"消息吞吐   {len(results.echo) / elapsed:.1f} 条/秒（按回显计）")
    if results.errors:
        print("\n错误明细:")
        for label, count in results.errors.most_common(10):
            print(f"  {count:>6}  {label}")
    if not results.echo and not results.relay and args.scenario != "connect":
        print("\n[警告] 没有采集到任何投递延迟样本，检查是否被限流或订单无效")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenario", choices=["connect", "echo", "relay"], default="echo")
    parser.add_argument("--users", type=int, default=50)
    parser.add_argument("--admins", type=int, default=1)
    parser.add_argument("--users-file", default=str(HERE / "chat-users.csv"))
    parser.add_argument("--admins-file", default=str(HERE / "chat-admins.csv"))
    parser.add_argument("--rate", type=float, default=1.0, help="每用户每秒发几条（服务端上限 5）")
    parser.add_argument("--duration", type=float, default=60, help="echo/relay 的发言时长(秒)")
    parser.add_argument("--hold", type=float, default=60, help="connect 场景保持连接的时长(秒)")
    parser.add_argument("--conns-per-user", type=int, default=1,
                        help="connect 场景下每个用户开几条连接（服务端上限 4）")
    parser.add_argument("--ramp", type=float, default=10, help="建连/启动的爬坡时长(秒)")
    parser.add_argument("--split-ms", type=float, default=100,
                        help="双峰拆分阈值：低于此值算同实例投递，高于算跨实例扇出")
    parser.add_argument("--idle", type=int, default=0,
                        help="背景空闲连接数：只挂连接不发消息，用来制造高并发连接压力")
    parser.add_argument("--mode", choices=["gateway", "direct"], default="gateway",
                        help="gateway=经网关(同/跨实例由 LB 决定，只能猜)；"
                             "direct=直连指定实例(同/跨实例是确定的，适合做对照)")
    parser.add_argument("--sender-port", type=int, default=8104, help="direct 模式下发言用户连的实例")
    parser.add_argument("--admin-port", type=int, default=8114, help="direct 模式下客服连的实例")
    parser.add_argument("--idle-port", type=int, default=8104, help="direct 模式下空闲连接连的实例")
    parser.add_argument("--internal-token", default="local-dev-internal-token-change-in-production",
                        help="direct 模式绕开网关，需要自己带 X-Internal-Token")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080, help="gateway 模式的端口；8080=网关")
    args = parser.parse_args()

    if args.rate > SERVER_RATE_LIMIT:
        print(f"[警告] --rate {args.rate} 超过服务端限流 {SERVER_RATE_LIMIT} 条/秒/用户，"
              f"超出部分会被拒并计入错误率")
    if args.users > 0 and args.scenario == "connect":
        total = args.users * args.conns_per_user
        print(f"[提示] 目标连接总数 {total}（{args.users} 用户 × {args.conns_per_user} 连接）；"
              f"单账号上限 {SERVER_MAX_CONN_PER_USER}，单用户 5 条/秒的限流在 connect 场景用不到")

    started = time.perf_counter()
    results = asyncio.run(run(args))
    report(results, args, time.perf_counter() - started)


if __name__ == "__main__":
    main()
