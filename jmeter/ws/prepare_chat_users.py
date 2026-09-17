"""为 WS 聊天压测准备用户：从 seckill_orders 取真实的 (user_id, order_no)，
用本地私钥铸一个 JWT，输出 token,userId,orderNo,email。

为什么要走真实订单：聊天服务在首次发言时会做
    SELECT COUNT(1) FROM seckill_orders WHERE order_no=? AND user_id=?
（ChatService.requireOrderOwner），订单不存在直接抛"订单不存在"。
所以虚拟用户必须绑定一条真实订单，不能凭空造 id。

**本脚本只读数据库，不写任何数据。**

    python prepare_chat_users.py --count 300
    python prepare_chat_users.py --count 300 --exclude-user 3   # 排除你自己的账号
"""

from __future__ import annotations

import argparse
import base64
import csv
import json
import time
from pathlib import Path

import pymysql
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

# 中文 Windows 上 Python 的 stdout 默认是 GBK，打印 ✓/→ 这类字符会抛
# UnicodeEncodeError 把脚本打断。这里降级成替换字符，保证不崩。
import sys as _sys
for _stream in (_sys.stdout, _sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(errors="replace")

HERE = Path(__file__).resolve().parent
DEFAULT_KEY = Path(r"C:\Users\27036\Desktop\seckill\seckill-cloud\keys\private.pem")


def b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def make_token(private_key, subject: int, email: str, ttl: int, role: int = 0) -> str:
    """与 auth-service 的 JwtTokenService 保持同样的 claim 结构（网关注重 type/sub/email/role）。

    role=1 是客服。客服连接会自动进 admin:hall，并且能用 subscribe 加入任意会话房间——
    这是"用户发消息 -> 客服收到"端到端延迟测量所必需的。
    """
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    payload = {"sub": str(subject), "email": email, "role": role, "tokenVersion": 0,
               "type": "access", "iat": now, "exp": now + ttl}
    eh = b64url(json.dumps(header, separators=(",", ":")).encode())
    ep = b64url(json.dumps(payload, separators=(",", ":")).encode())
    signature = private_key.sign(f"{eh}.{ep}".encode(), padding.PKCS1v15(), hashes.SHA256())
    return f"{eh}.{ep}.{b64url(signature)}"


def fetch_orders(args) -> list[tuple[int, str]]:
    """每个 user_id 取一条订单，保证虚拟用户之间不共享 userId。

    为什么不能共享：单账号有"最多 4 条连接 + 5 条/秒"的硬限制，
    多个虚拟用户共用一个 userId 会互相挤掉连接、触发限流，压测数据就失真了。
    """
    conn = pymysql.connect(host=args.mysql_host, port=args.mysql_port, user=args.mysql_user,
                           password=args.mysql_password, charset="utf8mb4")
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT o.user_id, MIN(o.order_no) AS order_no
                FROM seckill_order.seckill_orders o
                WHERE o.user_id NOT IN (%s)
                GROUP BY o.user_id
                ORDER BY o.user_id
                LIMIT %s
                """,
                (",".join(str(u) for u in args.exclude_user), args.count),
            )
            return [(int(uid), ono) for uid, ono in cur.fetchall()]
    finally:
        conn.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=300, help="需要多少个虚拟用户")
    parser.add_argument("--exclude-user", type=int, nargs="*", default=[3],
                        help="排除的用户 id（默认排除 3，即你自己的登录账号）")
    parser.add_argument("--output", default=str(HERE / "chat-users.csv"))
    parser.add_argument("--admin-count", type=int, default=2, help="生成几个客服账号（用于端到端投递测量）")
    parser.add_argument("--admin-output", default=str(HERE / "chat-admins.csv"))
    parser.add_argument("--admin-base", type=int, default=90000, help="客服虚拟 userId 起始值，避免与真实用户撞号")
    parser.add_argument("--private-key", default=str(DEFAULT_KEY))
    parser.add_argument("--ttl", type=int, default=14400, help="token 有效期(秒)")
    parser.add_argument("--mysql-host", default="127.0.0.1")
    parser.add_argument("--mysql-port", type=int, default=3307)
    parser.add_argument("--mysql-user", default="seckill")
    parser.add_argument("--mysql-password", default="seckill123")
    parser.add_argument("--mysql-db", default="seckill_order")
    args = parser.parse_args()

    rows = fetch_orders(args)
    if not rows:
        raise SystemExit("seckill_orders 里没取到可用订单；先用真实下单流程造几条订单")

    key = serialization.load_pem_private_key(Path(args.private_key).read_bytes(), password=None)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(["token", "userId", "orderNo", "email"])
        for user_id, order_no in rows:
            email = f"load{user_id}@test.local"
            writer.writerow([make_token(key, user_id, email, args.ttl), user_id, order_no, email])

    print(f"生成 {len(rows)} 个虚拟用户 -> {output}  (TTL {args.ttl}s)")
    print(f"user_id 范围 {rows[0][0]} ~ {rows[-1][0]}")

    if args.admin_count:
        # 客服账号不需要订单，只需要互不相同的 userId（同样受"单账号最多 4 连接"限制）
        admin_output = Path(args.admin_output)
        with admin_output.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle, lineterminator="\n")
            writer.writerow(["token", "userId", "email"])
            for index in range(args.admin_count):
                admin_id = args.admin_base + index
                writer.writerow([make_token(key, admin_id, f"cs{admin_id}@test.local", args.ttl, role=1),
                                 admin_id, f"cs{admin_id}@test.local"])
        print(f"生成 {args.admin_count} 个客服账号 -> {admin_output}")

    print("只读数据库，未写入任何数据。")


if __name__ == "__main__":
    main()
