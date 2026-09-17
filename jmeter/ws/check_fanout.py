"""检查聊天跨实例扇出的绑定是否完好——防止 2026-09-17 那个缺陷悄悄复发。

背景
----
`chat.fanout` 交换机的绑定曾经整个丢失过（见 README 第 7 节）：
队列 `seckill.order.close.delay` 的 `x-message-ttl` 与应用配置不一致，
启动时 RabbitAdmin 抛 PRECONDITION_FAILED 中断声明流程，绑定就没建成。
后果是跨实例消息 100% 静默丢弃——**日志里没有任何报错**，只有压测能发现。
触发条件很普通（改一次 ORDER_PAY_TIMEOUT 就可能复现），所以做成开机自检。

    python check_fanout.py            # 退出码 0=正常，1=绑定缺失
    python check_fanout.py --quiet    # 只在异常时输出

由 start-services.ps1 在启动末尾调用。

注意：**所有 print 出去的内容必须是纯 ASCII**。这个脚本会被 PowerShell 启动脚本
直接调用，此时 Python 的 stdout 在中文 Windows 上默认是 GBK，输出非 ASCII 字符
（哪怕是 ✓）会抛 UnicodeEncodeError，把正常的检查变成退出码非 0 的假警报。
"""

from __future__ import annotations

import argparse
import base64
import json
import sys
import urllib.error
import urllib.request

FANOUT_EXCHANGE = "chat.fanout"
QUEUE_PREFIX = "chat.fanout."
DELAY_QUEUE = "seckill.order.close.delay"
EXPECTED_TTL_MS = 900000  # 对应 app.order.pay-timeout-seconds=900


def api(base: str, path: str, user: str, password: str):
    request = urllib.request.Request(f"{base.rstrip('/')}{path}")
    token = base64.b64encode(f"{user}:{password}".encode()).decode()
    request.add_header("Authorization", f"Basic {token}")
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://127.0.0.1:15673", help="RabbitMQ management API")
    parser.add_argument("--user", default="admin")
    parser.add_argument("--password", default="admin123")
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument("--expected-instances", type=int, default=2,
                        help="expected number of chat.fanout.* queues (= order-service instances)")
    args = parser.parse_args()

    def say(message: str) -> None:
        # flush：异常路径走 stderr（无缓冲），不刷 stdout 会让两边顺序错乱
        if not args.quiet:
            print(message, flush=True)

    try:
        queues = api(args.api, "/api/queues", args.user, args.password)
        bindings = api(args.api, "/api/bindings", args.user, args.password)
    except (urllib.error.URLError, OSError, json.JSONDecodeError) as exc:
        print(f"[chat-fanout] cannot reach RabbitMQ management API {args.api}: {exc}", file=sys.stderr)
        print("[chat-fanout] skipping check (normal when RabbitMQ is not running)")
        return 0

    # 先看触发这个缺陷的根源：延迟队列 TTL 漂移
    delay = next((q for q in queues if q["name"] == DELAY_QUEUE), None)
    if delay is not None:
        ttl = (delay.get("arguments") or {}).get("x-message-ttl")
        say(f"[chat-fanout] {DELAY_QUEUE} x-message-ttl = {ttl}")
        if ttl is not None and int(ttl) != EXPECTED_TTL_MS:
            say(f"[chat-fanout] note: TTL differs from the app default {EXPECTED_TTL_MS}ms "
                f"(ORDER_PAY_TIMEOUT=900); if startup logs show PRECONDITION_FAILED, "
                f"the bindings may be lost again")

    fanout_queues = sorted(q["name"] for q in queues if q["name"].startswith(QUEUE_PREFIX))
    # source 为空字符串代表默认交换机；要的是 source == chat.fanout 的那种
    bound = {b["destination"] for b in bindings if b["source"] == FANOUT_EXCHANGE}

    problems: list[str] = []

    if not fanout_queues:
        problems.append("no chat.fanout.* queue found - order-service may be down, "
                        "or ChatTopology was not loaded")
    else:
        missing = [q for q in fanout_queues if q not in bound]
        if missing:
            problems.append(
                f"{len(missing)}/{len(fanout_queues)} fanout queue(s) not bound to the "
                f"{FANOUT_EXCHANGE} exchange: " + ", ".join(missing)
                + " | cross-instance chat messages will be dropped silently "
                  "(same-instance still works, so single-instance tests miss it)")

    if fanout_queues and len(fanout_queues) < args.expected_instances:
        problems.append(f"found {len(fanout_queues)} fanout queue(s), expected "
                        f"{args.expected_instances} (one instance may be down)")

    if problems:
        print("[chat-fanout] FAIL - fanout binding check did not pass:", file=sys.stderr)
        for item in problems:
            print(f"  - {item}", file=sys.stderr)
        print("[chat-fanout] fix: delete the queue whose arguments drifted, restart "
              "order-service; re-test with: python diag_relay.py", file=sys.stderr)
        return 1

    say(f"[chat-fanout] OK - {len(fanout_queues)} fanout queue(s) all bound: "
        f"{', '.join(fanout_queues)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
