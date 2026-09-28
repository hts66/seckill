# Seckill Cloud 项目全景手册

> 微服务秒杀商城 + 订单客服实时聊天系统。
> 技术栈：Spring Boot 3 / Spring Cloud Gateway / Nacos / RabbitMQ / Redis / MySQL / MyBatis-Plus（旧版）/ JdbcClient（微服务版）/ Vue 3 + Vite / WebSocket / RabbitMQ fanout / Sentinel / 支付宝沙箱。

---

## 1. 总体架构

```
                      ┌────────────────────────────┐
  浏览器/手机 ──────→ │ Vue3 + Vite 前端 :5173      │  useChat 全局单例 WebSocket
                      └────────────┬───────────────┘
                                   │ /api/** (HTTP)  /ws/chat (WS)
                      ┌────────────▼───────────────┐
                      │ Spring Cloud Gateway :8080 │  JWT 鉴权 + Sentinel 流控 + lb 路由
                      └──┬──────┬──────┬──────┬────┘
                         │      │      │      │
        ┌────────────────▼┐ ┌───▼────────▼──┐ ┌─▼──────────────────┐
        │ auth-service    │ │ product-      │ │ activity-service   │
        │ 注册/登录/JWT    │ │ service       │ │ 活动/秒杀引擎       │
        └─────────────────┘ │ 商品          │ │ Lua 库存/MQ 建单    │
                            └───────────────┘ └────────────────────┘
                         ┌──────────────────────────────────┐
                         │ order-service ×2 (8104/8114)     │
                         │ 订单/支付/钱包/超时关单/实时聊天    │
                         │ worker-id=0 / worker-id=1        │
                         └──────────────────────────────────┘
   基础设施：Nacos(注册/配置) · RabbitMQ(seckill.events / chat.fanout / 延迟队列)
             Redis(库存/已购/补偿标记) · MySQL 4 库 · Sentinel · AI 客服 :8200(可选)
```

- **网关路由**：`/api/auth/**`→auth；`/api/products/**`→product；`/api/seckill/**`→activity；`/api/orders/**`、`/api/addresses/**`、`/api/chat/**`→order；`/ws/chat`→order（lb:ws）；`/api/customer-service/**`→AI 服务（默认 :8200）。
- **order-service 双实例**：聊天雪花 ID 依赖 `worker-id` 区分（node8104=0，node8114=1），由 `scripts/start-services.ps1` 固定启动。
- **端口占用教训**：8080 曾被 Docker 容器（`seckill-cloud-seckill-gateway-1`）占用导致请求打到 Docker 转发层返回 404——排查手段：`netstat -ano | findstr :8080` + `tasklist /fi "PID eq xxx"`。

---

## 2. 各功能模块架构

### 2.1 认证（auth-service）
- 邮箱+密码注册、登录签发 JWT；`users.token_version` 支持令牌吊销；验证码接口。
- 网关全局过滤器校验 JWT，白名单外请求无 token 返回 401。

### 2.2 商品与活动（product / activity-service）
- 商品 CRUD（单表）；活动含 `preview_time`（预热期）→ `start_time` → `end_time` 生命周期。

### 2.3 秒杀引擎（activity-service，核心）
`SeckillEngine` 承担库存全生命周期：

| 阶段 | 机制 | 关键实现 |
|---|---|---|
| 预热 | 活动开始前把 `seckill_items.stock` 写入 Redis `seckill:stock:{id}`、已购集合 `seckill:users:{id}` | `setIfAbsent` 防并发覆盖 |
| 扣减 | **Redis Lua 原子四步**：判库存→判已购→DECR→SADD | 超卖/一人多买的主闸门 |
| 建单 | 发 MQ（`seckill.events`），order-service 消费按 `event_id` 幂等建单 | confirm 回调确认，失败重发≤3 次 |
| 补偿 | 发送失败→回补库存；confirm 丢失→`sweepUnconfirmed` 30s 扫描；消费失败→释放库存 | 补偿 marker `setIfAbsent` 7 天防重复回补 |
| 关单 | 延迟队列 `x-message-ttl` + 定时扫表兜底，CAS `WHERE status=0` 关单并释放库存 | 多实例并发扫描安全 |

**锁使用全景**：Redis Lua（分布式原子）→ ChatIdGenerator/ChatRateLimiter 的 `synchronized`（JVM 细粒度）→ 订单状态机/钱包扣款 DB CAS → 唯一键兜底（并发建会话/开户）。**没有用 Redisson**——能用 DB 行锁/唯一键解决的不引入锁组件，临界区不跨资源就不用分布式锁。

### 2.4 订单与支付（order-service）
- 状态机：`status` 0-待支付 →1-已支付 / 2-已取消 / 已关闭；`fulfillment_status` 0→2-已发货→3-确认收货。全部 CAS 更新（`WHERE status=旧值`），多实例安全。
- 支付通道：**虚拟钱包为唯一支付通道**（`POST /api/orders/pay/{orderNo}` 由 WalletController 承接 → WalletService CAS 扣款 `balance>=:amt` 防超支，取消/退款同事务回滚加回）。**支付宝沙箱已停用**（`app.alipay.enabled` 默认 false），PaymentController/PaymentService 已删除，仅余禁用状态的配置与 SDK 残留（见 §6 风险 8）。
- 超时关单：延迟队列 TTL（900s）触发 + 定时扫描兜底。

### 2.5 实时聊天（order-service，单/多实例）
- **发送**：WS 帧 → 限流（5 条/秒，`synchronized(用户 Window)` 细粒度锁）→ 建会话（订单号唯一键兜底并发）→ 雪花 ID → `offer` 入有界队列（1 万，满则"系统繁忙"背压）→ **本机房间即时广播**（零延迟）。
- **落库**：`@Scheduled` 每 200ms `drainTo(200)` → 批量 INSERT → 按会话聚合 UPDATE 未读/摘要 → **落库成功后**才 fanout。
- **多实例**：`chat.fanout` 交换机 + 每实例 exclusive auto-delete 队列；信封两种：BATCH（消息+会话快照）/ CHANGED（只带会话 id 回查）；`origin` 字段防发起实例重复推送。
- **在线/离线**：连接建立自动加入用户全部会话房间（上线即收实时）；历史走 REST 游标分页（`beforeId`+`size≤30`），前端按雪花 ID 去重合并。
- **未读角标**：用户端 30s 轮询 `/chat/unread-counts`；客服端大厅 `admin:hall` 房间实时推快照。
- **保活**：30s 心跳 / 90s 僵尸连接清理 / 单账号 4 连接上限。
- **顺序不变量**：同实例"先广播后落库"（交互时序保护，幻影消息不可达）；跨实例"先落库后广播"（广播过的必可查，MQ 仅通知层可优雅降级）。

### 2.6 运维自检
- `start-services.ps1` 末尾跑 `jmeter/ws/check_fanout.py`：检查 chat.fanout 绑定是否丢失（曾因延迟队列 TTL 漂移导致 RabbitAdmin 声明中断、跨实例消息静默丢弃，见 `jmeter/ws/README.md` §7）。
- `jmeter/ws/`：WebSocket 压测脚本 + `diag_relay.py` 中继诊断 + 合格线（跨实例投递 p95≤400ms、配对率≥99%）。

---

## 3. 数据流动（三条主链路）

### 3.1 秒杀下单流
```
用户抢购 → 网关(JWT/Sentinel) → activity SeckillEngine
  → Lua 原子扣减 Redis 库存(-1) + 记已购(SADD)
  → 发 MQ seckill.events(event_id)          ← 异步解耦
  → order-service 消费者: uk_event 幂等 → INSERT seckill_orders → 发 confirm
  → 用户支付: 钱包 CAS 扣款/支付宝沙箱 → payments 流水 → 订单 status→1
超时未付: 延迟队列 TTL → CAS 关单 → SREM+INCR 释放 Redis 库存
发送失败: confirm 超时/失败 → 补偿回补库存(7 天 marker 防重)
```

### 3.2 聊天消息流
```
发送方 → WS → 限流 → 雪花ID → 入内存队列 ─┬→ 本机房间即时广播(0ms, 同实例接收者)
                                          │
        200ms 批量: INSERT chat_message    │
        + 聚合UPDATE unread/last_content  ←┘
        → fanout chat.fanout → 各实例队列
             ├ 发起实例(origin=自己): 只刷 admin:hall 大厅
             └ 其它实例: 推本机 conv:{id} 房间(跨实例接收者, ~200ms)
离线用户: 上线自动加入全部会话房间(增量) + REST 游标拉历史(存量) + 雪花ID去重
```

### 3.3 未读数流
```
落库时: 同会话聚合 unread_user/unread_admin 增量 UPDATE
用户端角标: 30s 轮询 GET /chat/unread-counts (unread_user>0 的会话)
客服端角标: admin:hall 房间实时快照推送 + 置顶
已读: 打开面板 → POST read 清零(异步) → CHANGED 信封同步各实例
```

---

## 4. 数据与存储设计

### 4.1 存储分工（"什么数据放哪"）
| 数据 | 存储 | 原因 |
|---|---|---|
| 库存余量/已购集合 | Redis | 秒杀原子扣减，10 万级 QPS |
| 补偿/预热 marker | Redis（TTL 7d） | 幂等去重 |
| 订单/支付/钱包/地址/聊天 | MySQL | 强一致事实源 |
| 消息实时帧 | RabbitMQ（非持久化） | 只做在线通知，丢了靠历史补偿 |
| 建单事件 | RabbitMQ（seckill.events） | 异步削峰 |
| 关单延迟 | RabbitMQ TTL 队列 | 免轮询定时触发 |

### 4.2 数据库表设计（4 库 11 表）

**seckill_auth**
- `users`：邮箱唯一 `uk_users_email`；`token_version` 吊销令牌；`deleted_at` 软删除；`idx_users_status_role(status,role)`。

**seckill_product**
- `products`：单表，`price/stock`，商品图片 TEXT。

**seckill_activity**
- `seckill_activities`：`preview_time/start_time/end_time` + `idx_time` 三列联合索引支撑"预热/进行中/已结束"列表查询。
- `seckill_items`：`activity_id/product_id` 外联 + 冗余 `product_name/title/images` 快照（**避免秒杀详情三表 JOIN**）；`seckill_price`、`stock`、`limit_per_user`（限购，当前按 1 实现）。

**seckill_order**（业务最重的库）
- `seckill_orders`：三个唯一键是防超卖/幂等的核心——`uk_event(event_id)` MQ 重复投递幂等、`uk_user_item(user_id,item_id)` 一人一件、`uk_order_no(order_no)` 订单号；收货人信息快照内联（下单即固化）；各状态时间列；`idx_user(user_id)` 撑"我的订单"。
- `payments`：**历史遗留表**（支付宝沙箱通道已停用，无对应 Controller，表内数据不再产生）；`channel` 默认值仍是 'ALIPAY_SANDBOX'。可考虑归档删除。
- `user_wallets`：`user_id` 主键（与用户同 id）；`balance DECIMAL(12,2)`。
- `wallet_transactions`：带符号 `amount` + `balance_after` 快照，可对账重放；`type` PAY/REFUND/ADJUST。
- `user_addresses`：`idx_address_user_default(user_id,is_default)` 撑默认地址查询。
- `chat_conversation`：`uk_chat_order(order_no)` 一订单一会话（并发建会话的兜底锁）；`unread_user/unread_admin` 双向未读计数；`last_content/last_at` 会话摘要；`idx_chat_last` 撑客服大厅按最近排序。
- `chat_message`：`idx_chat_msg_conv(conversation_id,id)` 联合索引**精确覆盖游标分页**（`WHERE conversation_id=? AND id<? ORDER BY id DESC`）；注意消息主键是自增，**业务消息 ID（雪花）在应用层生成**，落库前就要给前端。

### 4.3 设计要点
- **按服务分库**（auth/product/activity/order 各一库），服务间不跨库 JOIN，跨服务数据用**快照冗余**（秒杀商品快照、订单收货人快照、会话冗余 user_email）。
- **唯一键即锁**：并发场景优先用 DB 唯一键 + DuplicateKey 回查，而非显式加锁。
- **时间列即状态轨迹**：pay_time/cancel_time/shipping_time/finish_time 配合 status CAS 形成完整审计。

---

## 5. 已实现 / 未实现

### 已实现
- 注册登录、JWT、验证码、令牌版本吊销
- 商品管理、活动/场次管理、管理端后台
- 秒杀全链路：预热、Lua 原子扣减、MQ 异步建单、幂等消费、超时关单（TTL+扫表双保险）、补偿回补、confirm 对账
- 防超卖双保险：Redis Lua（运行期）+ `uk_event`/`uk_user_item` 唯一键（DB 兜底）
- 虚拟钱包支付（余额支付/退款/流水对账；支付宝沙箱已停用，支付入口为 WalletController）
- 订单状态机（取消/退款/发货/确认收货）、收货地址管理
- 实时聊天：单实例批量落库、多实例 fanout、未读角标、游标历史、心跳保活、僵尸清理、限流、限连接
- 网关统一鉴权 + Sentinel 流控 + fanout 绑定开机自检 + WebSocket 压测工具链

### 未实现 / 已知缺口
| 缺口 | 影响 | 建议方案 |
|---|---|---|
| **DB 库存数量兜底表** | Redis 数据错误/重启重置时 MySQL 无法独立校验超卖 | 建 `seckill_stock_occupy` 表，建单事务内 `available>0` 条件扣减（优先级最高） |
| **预热从 DB 重建** | Redis 重启后按初始库存重新预热 → 已卖出部分被重置 → 超卖窗口 | 预热值 = `items.stock - COUNT(有效订单)`；Redis 开 AOF everysec |
| **库存回补时机** | cancel 事务提交前就回补 Redis，回滚会造成多放库存 | `@TransactionalEventListener(AFTER_COMMIT)` + 独立 Bean（防自调用 AOP 失效） |
| Outbox 本地消息表 | DB 成功但 MQ 状态不明的推测逻辑较多 | 建单事务内写 outbox，轮询投递（当前补偿体系可收敛，暂缓） |
| 限购数 >1 | Lua 只实现一人一件 | 脚本内按 `limit_per_user` 计数 |
| 真实支付渠道 | 仅虚拟钱包；支付宝沙箱代码/SDK/配置残留（enabled=false 死配置） | 清理 AlipayConfig/AlipayProperties/alipay-sdk 依赖/payments 表；或对接真实网关 |
| 物流对接 | fulfillment_status 有状态无真实物流 | 接物流 API |
| 聊天丢批兜底 | flush 失败该批永久丢失（内存） | 本地 WAL 先落盘 / 接 MQ 持久化中转 |
| 分库分表/读写分离 | 单实例 MySQL | 量级到了再说 |
| 分布式限流/锁(Redisson) | 限流为 JVM 内 | WebSocket 有状态、单实例判断已足够，暂不需要 |

---

## 6. 潜在风险清单

1. **超卖窗口（最重要）**：Redis 重启 → 预热重置 → 超卖。见上表前两行，必须补。
2. **绑定静默丢失**：RabbitMQ 队列参数漂移（TTL）→ RabbitAdmin 声明中断 → fanout 消息静默丢弃、日志无报错。已有开机自检缓解，根治可在发送端做"绑定存在性断言"。
3. **端口占用**：8080 被 Docker/WSL 占用时网关请求被劫持（已踩坑），启动脚本可加端口预检。
4. **聊天丢批**：flush 异常该批仅打 warn；影响=历史缺失，实时已送达。可接受但要有日志告警。
5. **cancel 回补时序**：事务提交前回补 Redis，理论窗口造成多放一件。低概率，方案见 §5。
6. **雪花时钟回拨**：ChatIdGenerator 靠 synchronized 等待，长时间回拨会阻塞；双实例 worker-id 配错会 ID 冲突（`start-services.ps1` 已固化）。
7. **前端轮询未读**：30s 轮询在用户量大时是稳定的 QPS 底噪；可改 WS 推未读事件。
8. **single point**：Nacos/RabbitMQ/MySQL 均单点，生产需集群。
9. **支付宝沙箱残留（已停用功能）**：支付主链路（PaymentController/Service）已删除，支付唯一走虚拟钱包，但残留以下死代码/死配置/过时信息，全部处于不生效状态：
   - `AlipayConfig` / `AlipayProperties`（`@ConditionalOnProperty app.alipay.enabled=true` 才装配，默认 false）
   - `pom.xml` 的 `alipay-sdk-java:4.40.560` 依赖（约几 MB 无用 jar）
   - `application.yml` 的 `app.alipay` 段（notify-url 还指向已不存在的 `/api/payments/alipay/notify`）
   - `payments` 表与 `channel='ALIPAY_SANDBOX'` 默认值；`.env.example`/`docker-compose.yml` 环境变量（`_diag/` 调试脚本与根目录旧单体残留已于 2026-09-28 清理）
   - **`ai-service/app/faq.json` 仍在告诉用户"支持支付宝沙箱支付、原路退款"——与现状矛盾，AI 客服会给出错误答案，应优先更新**

---

## 7. 性能优化建议

### 已做对的事（保持）
- 秒杀：Lua 原子扣减（临界区微秒级）+ MQ 异步建单 + 库存预热 + Sentinel 前置限流
- 聊天：有界队列背压 + 200ms 批量刷盘 + 聚合 UPDATE（50 条消息 1 条 SQL）+ 游标分页覆盖索引
- 订单：CAS 替代悲观锁、快照冗余避免跨库 JOIN、唯一键兜底并发

### 可做的优化（按性价比排序）
| 优化点 | 现状 | 方案 | 预期收益 |
|---|---|---|---|
| 未读角标推拉结合 | 用户端 30s 轮询 | WS 连接上推未读事件（已有通道，加个帧类型） | 消灭底噪 QPS，角标实时 |
| flush 节奏自适应 | 固定 200ms | 队列积压>阈值时缩到 50ms，空闲拉长到 1s | 跨实例延迟与 DB 压力动态平衡 |
| 商品/活动列表缓存 | 每次查库 | Redis 缓存 + 预热期刷新（注意与秒杀库存 key 区分） | 列表页 QPS 大幅提升 |
| 订单列表深分页 | OFFSET 式 | 游标分页（同聊天方案） | 深页不劣化 |
| 历史消息批量小优化 | 每会话一条聚合 UPDATE | 已够用；量大可攒批合并 | 边际收益 |
| 热点库存分段 | 单 key Lua | 极热点时分段库存（stock:1..N 汇总） | 突破单 key 10 万 QPS |
| 前端静态资源 | Vite dev 5173 | build + Nginx/CDN | 生产必备 |
| MySQL 高可用 | 单点 | 主从+MHA/半同步 | 可用性 |

---

## 8. 面试高频问答速查

- **为什么 Lua 不用 Redisson？** 临界区不跨资源、无需阻塞等待；Lua 原子区间微秒级 vs 锁串行整流程 50ms。
- **多实例要不要换分布式锁？** 不用。Lua 依赖 Redis 服务端单线程，与调用方实例数无关；多实例真正的难点是消息幂等（event_id/marker/CAS 已覆盖）。
- **同实例为什么敢先广播后落库？** 接收者屏幕被实时流覆盖不查库；所有查历史入口是人工重操作，时序天然晚于 200ms 窗口——幻影消息"窗口存在但不可达"。
- **跨实例为什么必须先落库？** 广播过的必可查（强不变量）；且跨实例广播只能走 MQ，先广播会把 MQ 提升为数据层，丢批影响面放大到全集群，换来的只是无感的 200ms。
- **异步落库怎么讲？** 生产者-消费者：发送只入队（纳秒级+有界背压），后台 200ms 一批 200 条，批量 INSERT+聚合 UPDATE；代价是宕机丢内存批，聊天可接受、订单不可接受——可靠性按业务分级。
- **事务四大特性具备吗？** 单库内具备（钱包 CAS/状态机）；跨资源主动放弃强一致，用"短事务+幂等+补偿"达成 BASE 最终一致。
