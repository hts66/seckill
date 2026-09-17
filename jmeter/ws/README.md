# 订单客服实时聊天（WebSocket）压测方案

测量 `/ws/chat` 上「用户发出消息 → 对方收到」的速度，以及连接规模上限。

---

## 1. 测什么

| 指标 | 含义 | 用户感知 |
|---|---|---|
| **握手耗时** | WS 升级 + 收到 `connected` 帧 | 打开订单页点客服按钮后的等待 |
| **消息回显延迟** | 发出 → 自己收到该消息 | 发言后的即时反馈 |
| **端到端投递延迟** | 用户发出 → **客服**收到 | 真正决定「客服多久能看到」 |
| **连接容量** | 能同时保持多少条 WS 连接 | 系统能承载多少在线用户 |
| **消息吞吐** | 条/秒 | 峰值能扛多少发言 |

---

## 2. 服务端硬约束（压测必须绕着走）

这些都写死在代码里，压测脚本不绕开就会得到失真的数据：

| 约束 | 值 | 出处 | 对压测的影响 |
|---|---|---|---|
| 单用户发言限流 | **5 条/秒**（固定窗口） | `ChatRateLimiter` | 超过会收到 `{"type":"error","message":"发言太频繁"}`，计入错误率 |
| 单账号连接上限 | **4 条** | `app.chat.max-connections-per-user` | **虚拟用户之间不能共用 userId**，否则互相挤掉连接 |
| 空闲超时 | 90s 无活动踢线，每 30s 扫描 | `ChatWebSocketHandler.evictIdleConnections` | 保活场景要发 `{"type":"ping"}` |
| 落库背压 | 队列 10000，每 200ms flush 最多 200 条 | `ChatPersistence` | 单实例落库上限 ≈ **1000 条/秒**，超了收「消息系统繁忙」 |
| 消息长度 | ≤ 1000 字符 | `ChatService.buildPendingMessage` | 压测内容要短 |

---

## 3. 架构决定了必须分两种场景测

```
用户 ──┐                                    ┌── 客服
       ├─► 网关 8080 ──LB──► order-service A (8104) 房间 conv:123
       │                       │  │
       │                       │  └─ 本地广播（毫秒级）
       │                       │
       │                   落库(每200ms批量) ──► RabbitMQ fanout ──► order-service B (8114) ──► 客服
       │                                                              （百毫秒级）
```

**关键**：发起实例在 WebSocket 线程里**立即本地广播**，不走 MQ；
只有在服务端**落库之后**（最多 200ms 延迟）才经 RabbitMQ 扇出给其它实例。

所以两条路径的投递延迟差 **60 倍**（用 `--mode direct` 固定端口实测，非推断）：

| 路径 | p50 | p95 | p99 |
|---|---|---|---|
| 同实例（本地房间广播） | **3.4 ms** | 6.6 ms | 10.1 ms |
| 跨实例（等 flush + MQ 扇出） | **202.0 ms** | 321.2 ms | 367.8 ms |

> 跨实例的 p50 = 202ms，几乎正好等于 `app.chat.flush-interval-ms: 200`——
> **跨实例延迟完全由批量落库的 flush 间隔主导**，RabbitMQ 本身只占零头。
> 想改善跨实例时延，调这个参数比换 MQ 有效得多。

**这意味着：只连一个实例压测毫无意义**（本地广播是 `ConcurrentHashMap` 遍历，测不出东西）。

### ⚠️ 别拿延迟阈值去猜同/跨实例

经网关压测时，同/跨实例由 LoadBalancer 决定，客户端不知道。曾想用「<100ms 算同实例、
≥100ms 算跨实例」来推断——**这是错的**。用 `--mode direct` 拿到真值后验证：

| 链路 | <100ms 占比 |
|---|---|
| 纯同实例（8104→8104） | 100.0% |
| 纯跨实例（8104→8114） | **14.3% ~ 16.9%** |

跨实例消息若刚好赶在 flush 触发前到达，同样能落在 100ms 以内。
所以阈值分组**只能当分布展示，不能当分类判据**。要做同/跨对照，必须用 `--mode direct` 固定端口。

---

## 4. 为什么不用 JMeter

1. 本机 JMeter 没装 WebSocket 采样器插件（`lib/ext` 下没有）。
2. 千级连接：JMeter 一条连接一个线程，要上分布式压测；asyncio 单进程就够。
3. 「用户发出 → 客服收到」要跨两条连接做时间戳配对，JMeter 多线程关联极其别扭。
4. 逐帧到达时间戳（握手/回显/投递分开计时）JMeter 给不了。

用 Python `websockets`（asyncio）。

---

## 5. 用法

### 5.1 准备虚拟用户（只读数据库，零写入）

```bash
python prepare_chat_users.py --count 300 --admin-count 2
```

从 `seckill_orders` 取 300 组 `(user_id, order_no)`，用本地私钥铸 JWT。

> **不需要造数据**：聊天只校验订单归属（`ChatService.requireOrderOwner`），
> **不校验用户是否存在于 `seckill_auth.users`**。库里 `seckill_orders` 有 334 个不同
> `user_id`（范围 3~9529）可直接用，而 `users` 表只有 4 行——所以全程不碰数据库。
> 首条消息时服务端会按 `order_no` 自动建会话。

### 5.2 三种场景

```bash
# A. 连接容量：500 条连接，20s 爬坡，保持 60s
python ws_chat_load.py --scenario connect --users 500 --ramp 20 --hold 60

# B. 消息回显：100 用户各 1 条/秒，跑 60s
python ws_chat_load.py --scenario echo --users 100 --rate 1 --duration 60

# C. 端到端投递（重点）：20 用户 + 1 客服，经网关（真实链路）
python ws_chat_load.py --scenario relay --users 20 --admins 1 --rate 1 --duration 30 --ramp 5

# D. 同/跨实例对照实验：直连固定端口，分类是真值而不是推断
python ws_chat_load.py --scenario relay --users 20 --admins 1 --rate 1 --duration 25 \
  --mode direct --sender-port 8104 --admin-port 8104     # 同实例
python ws_chat_load.py --scenario relay --users 20 --admins 1 --rate 1 --duration 25 \
  --mode direct --sender-port 8104 --admin-port 8114     # 跨实例

# E. 高并发连接压力：挂 300 条只收不发的背景连接，再测投递
python ws_chat_load.py --scenario relay --users 20 --admins 1 --rate 1 --duration 25 --ramp 8 \
  --mode direct --sender-port 8104 --admin-port 8114 --idle 300 --idle-port 8104
```

> `--idle` 会用**没被发言占用**的用户，不会把发言用户的 4 条连接额度吃满。
> 两者共用同一批用户会被服务端以「连接数过多」拒绝，测量直接作废。

### 5.3 诊断脚本（验证修复、定位问题时用）

```bash
python diag_relay.py            # 走网关，1用户+1客服，逐帧打印，验证跨实例投递
python diag_same_instance.py    # 直连 8104，同实例对照
```

---

## 6. 实测基线（双实例 + 网关，2026-09-17）

20 用户 + 1 客服，1 条/秒/用户，30s：

```
建连成功 20   失败 0   中途掉线 0
发出 623 条

握手耗时   p50=13ms   p95=25ms
回显延迟   p50=5.1ms  p95=9.4ms   p99=12.6ms
投递延迟   p50=21.8ms p90=244.9ms p99=300.7ms
  ├ 同实例  359 条 59.5%  p50=5.6ms    p95=84.6ms
  └ 跨实例  244 条 40.5%  p50=204.1ms  p95=285.4ms
```

### 连接容量

| 连接数 | 建连成功 | 失败 | 中途掉线 | 握手 p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|---|
| 333 | 333 | 0 | 0 | 9 ms | 14 ms | 128 ms | 341 ms |
| 999 | 999 | 0 | 0 | 8 ms | 11 ms | 25 ms | 114 ms |
| **1332** | **1332** | **0** | **0** | **8 ms** | **11 ms** | **15 ms** | 51 ms |

1332 = 333 用户 × 4 连接（单账号上限），是**现有测试用户能触达的最大值**。
三档全部零失败零掉线，握手时延不随连接数增长——**容量上限在 1332 以上，尚未探到**。
要往上压需要先造更多虚拟用户（`seckill_orders` 里现有 333 个不同 `user_id` 已用尽）。

> 注意 333 那档的 p99=128ms 反而比 999/1332 高，是首次连接时网关/实例的冷启动造成的
> （该档是重启后第一次跑）。对比不同档位时要留意这个顺序效应。

### 高并发连接下的投递延迟（重点）

连接数上去之后，投递会不会变慢？**不会。** 用 `--idle` 挂住背景连接，测量期间的并发压力：

**同实例（发言 8104 / 客服 8104）**

| 背景空闲连接 | 配对样本 | p50 | p95 | p99 |
|---|---|---|---|---|
| 0 | 403 | 3.4 ms | 6.6 ms | 10.1 ms |
| 300 | 508 | **3.4 ms** | **6.9 ms** | 10.8 ms |

**跨实例（发言 8104 / 客服 8114）**

| 背景空闲连接 | 配对样本 | p50 | p95 | p99 |
|---|---|---|---|---|
| 0 | 406 | 202.0 ms | 321.2 ms | 367.8 ms |
| 300 | 507 | 206.4 ms | 344.2 ms | 376.4 ms |
| 900 | 504 | **187.0 ms** | 322.4 ms | 350.5 ms |

**结论：连接数从 0 加到 900，两条路径的投递延迟都没有可测量的劣化。**
900 那档的 p50 甚至比 300 那档还低——**运行间的波动（±5%）盖过了负载效应**。
对照 300 vs 900 的建连成功率（均 0 失败）和掉线数（均 0），可以确认这不是"压力还没上来"。

原因也说得通：空闲连接只是挂在那里占一个 session 和一个房间集合，
既不消耗事件循环的 CPU，也不进落库队列。真正决定投递延迟的是
**flush 间隔**（跨实例）和**广播扇出**（同实例），两者与连接总数无关。

> 这也说明：如果线上出现"人一多消息就慢"，别先怀疑连接数，
> 去查落库队列积压和 flush 耗时。

**经网关全路径 + 300 连接**（同/跨混合，真实链路）：

```
投递延迟  p50=7.8ms  p90=231.7ms  p95=259.8ms  p99=286.7ms
  ├ <100ms   303 条 60.1%
  └ >=100ms  201 条 39.9%
```

60/40 的分布对应 LB 把 20 个发言用户大致均分到两个实例（客服固定在某一个）。
真正的瓶颈仍是跨实例那半边的 flush 等待。

**合格线**（供后续回归对照）：

| 检查项 | 合格线 |
|---|---|
| 握手 p95 | ≤ 100 ms |
| 同实例回显 p95 | ≤ 50 ms |
| 同实例投递 p95 | ≤ 100 ms |
| 跨实例投递 p95 | ≤ 400 ms（≈ flush 200ms + 落库 + 扇出） |
| 建连失败率 / 掉线数 | 0 |
| 错误率 | < 0.1% |
| 配对率 | ≥ 99%（发出条数 vs 客服收到条数） |

---

## 7. 压测中发现并修复的缺陷：跨实例扇出完全失效

**症状**：经网关压测时客服收到 **0/12** 条消息，而同实例直连对照是 **10/10**。

**根因**：`chat.fanout` 交换机**没有任何绑定**，两个实例队列只挂在默认交换机上，
`ChatPersistence.fanoutBatch` 的 `convertAndSend("chat.fanout", ...)` 发进了一个
无人订阅的交换机，被 RabbitMQ **静默丢弃**（失败被 catch 住只打 warn，日志里看不出来）。

```
(默认交换机) -> chat.fanout.node8104 / node8114      ← 错误状态
chat.fanout  -> (无绑定)
seckill.events -> order.close / order.created / ...   ← 对照，正常
```

**触发链**：队列 `seckill.order.close.delay` 现存 `x-message-ttl=20000`，
而应用配的 `app.order.pay-timeout-seconds=900` 要求 900000，启动时反复抛
`PRECONDITION_FAILED - inequivalent arg 'x-message-ttl'`，
导致 RabbitAdmin 的声明流程中断，**后续的绑定声明没跑完**。
（`seckill.events` 的绑定还在，是因为它们是 durable 的、早前成功运行时建的。）

**修复**：删掉积压为 0 的 `seckill.order.close.delay` 队列并重启实例，
应用以正确 TTL 重建该队列，绑定随即出现：

```
chat.fanout -> ['chat.fanout.node8104', 'chat.fanout.node8114']   ✅
```

复测：客服 12/12 全收到，大厅 `conversation` 帧也开始到达。

### 回归防护（已做成自动检查）

这个缺陷最危险的地方在于**没有任何显式报错**，只有压测能发现。
所以做了 `check_fanout.py` 并**接进了 `start-services.ps1`**——每次启动自动校验：

```
[chat-fanout] seckill.order.close.delay x-message-ttl = 900000
[chat-fanout] OK - 2 fanout queue(s) all bound: chat.fanout.node8104, chat.fanout.node8114
```

绑定缺失时退出码 1 并打印修复指引，启动脚本会把它转成醒目的 Warning。

手动跑：

```bash
python check_fanout.py                    # 退出码 0=正常 1=异常
python check_fanout.py --quiet            # 只在异常时输出
python check_fanout.py --expected-instances 3   # 期望的实例数（默认 2）
```

它同时盯着**触发条件本身**：延迟队列的 `x-message-ttl` 是否又和应用默认值漂移。
改完 `ORDER_PAY_TIMEOUT` 或任何队列参数后，重启时就会看到提示。

---

## 8. 已知坑

| 坑 | 后果 | 规避 |
|---|---|---|
| **只测单实例** | 跨实例扇出坏了也测不出来（本地广播永远快） | 必须经网关 8080 跑 `relay` |
| **共用 userId** | 单账号 4 连接上限，虚拟用户互相挤掉 | 每个虚拟用户一个独立 userId（脚本已保证） |
| **rate > 5** | 触发限流，大量 error 帧，数据失真 | 脚本会告警；默认 1 条/秒 |
| **不拆双峰看 p50** | p50 被同实例的样本拉低，掩盖跨实例的 200ms | 报告已自动拆分同实例/跨实例 |
| **长连接不开心跳** | 90s 后被服务端踢掉 | `connect` 场景脚本每 30s 发 ping |
| **fanout 无持久化** | 实例重启期间的消息靠 REST 历史补偿 | 属设计取舍，压测时注意中途重启实例会丢实时帧 |
| **admin 未订阅就发消息** | 客服收不到（不在房间内） | `relay` 场景已做预热 + 订阅 + 2s 等待 |
| **背景连接和发言用户共用** | 背景连接把发言用户的 4 条额度吃满，发言连接全被拒，测量作废 | `--idle` 自动改用未占用的用户池；注意观察 `建连成功` 数是否等于 `发言数 + idle 数` |
| **用延迟阈值判定同/跨实例** | 跨实例消息有 14~17% 会落在 100ms 内，分类不可靠 | 要做同/跨对照就用 `--mode direct` 固定端口 |
| **脚本输出非 ASCII** | 中文 Windows 下 Python stdout 是 GBK，打印 `✓`/`→` 会抛 `UnicodeEncodeError`，把成功的检查变成退出码非 0 的**假警报** | 被自动化调用的 `check_fanout.py` 输出纯 ASCII；其余脚本加了 `reconfigure(errors="replace")` 兜底 |
| **PowerShell 脚本写中文** | PowerShell 5.1 按 GBK 读无 BOM 的 UTF-8 文件，中文字符串会直接把脚本解析崩掉 | `start-services.ps1` 保持纯 ASCII（脚本本来就是英文） |

---

## 9. 文件

```
jmeter/ws/
├── README.md                 本方案
├── prepare_chat_users.py     从真实订单生成虚拟用户（只读库）
├── ws_chat_load.py           压测器（connect / echo / relay 三场景）
├── check_fanout.py           扇出绑定自检（被 start-services.ps1 调用）
├── diag_relay.py             走网关的逐帧诊断，验证跨实例投递
├── diag_same_instance.py     直连单实例的对照实验
├── smoke.py                  最小连通性检查
├── chat-users.csv            生成物（300 虚拟用户，勿入库）
└── chat-admins.csv           生成物（2 个客服账号）
```
