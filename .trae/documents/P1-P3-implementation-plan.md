# P1-P3 实现计划

## Context
P0 交易闭环（虚拟钱包/超时关单/确认收货/退款）已完成并实测通过。用户要求继续实现 P1（电商常规业务）、P2（营销与用户侧）、P3（技术加分项），共 10 个功能模块。

## 现有可复用基础设施
- **MinIO 上传**：product-service 已有 `/api/admin/products/upload`（仅限管理员），需新增用户端上传端点
- **邮件发送**：auth-service 的 `EmailCodeService` 用 `JavaMailSender`，order-service 也配了 `spring.mail`，可直接发邮件
- **WebSocket fanout**：order-service 已有 `chat.fanout` 交换机 + 实例独占队列，可复用于通知推送
- **聊天会话**：`chat_conversation` 表有 `order_no` 关联订单，`chat_message` 支持 `sender_type=2` 系统消息
- **Redis**：key 前缀已用 `seckill:*`、`auth:*`、`chat:*`
- **前端**：Vue3 + axios(baseURL `/api`) + Pinia，已有 ImageUpload 组件、ChatPanel 组件

---

## P1：电商常规业务

### P1.1 订单评价/晒单
**SQL** `sql/10-review.sql` → `seckill_order.product_reviews`(
  id PK, order_no UK, user_id, item_id, rating TINYINT(1-5), content TEXT, images TEXT(JSON),
  created_at; KEY idx_item(item_id))

**后端**（order-service）
- `ReviewController.java`：
  - `POST /api/reviews` — 已完成订单(fulfillment=3)才能评价，一单一评(UK保证)
  - `GET /api/reviews/{itemId}` — 公开，分页返回评价列表含用户名/头像
- `UploadController.java`（product-service 或 order-service）：
  - `POST /api/upload/image` — 用户端图片上传到 MinIO `reviews/` 路径

**前端**
- `Orders.vue`：已完成订单卡片增加"评价晒单"按钮 → 弹出评价表单（星级+文字+ImageUpload组件）
- `SeckillDetail.vue`：底部增加评价区，展示该商品的所有评价

### P1.2 物流信息
**SQL** `sql/11-logistics.sql` → ALTER seckill_orders ADD express_company VARCHAR(50), tracking_no VARCHAR(50) AFTER shipping_time

**后端**
- `OrderController.ship` 改为接收 `{expressCompany, trackingNo}` body
- 新增 `LogisticsController.java`：
  - `GET /api/orders/{orderNo}/logistics` — 返回模拟物流轨迹（基于 shipping_time 生成时间线节点）

**前端**
- `Orders.vue`：已发货订单显示快递公司+运单号+物流轨迹折叠面板

### P1.3 售后申请审批流
**SQL** `sql/12-after-sale.sql` → `seckill_order.after_sales`(
  id PK, order_no, user_id, type TINYINT(0退款/1退货退款), reason VARCHAR(500), status TINYINT(0待审批/1通过/2拒绝/3已完成),
  admin_note VARCHAR(500), created_at, processed_at; KEY idx_user(user_id), KEY idx_status(status))

**后端**（order-service）
- `AfterSaleController.java`：
  - `POST /api/orders/{orderNo}/after-sale` — 用户发起售后，同时往 chat_conversation 发系统消息(sender_type=2)
  - `GET /api/after-sales` — 用户查看自己的售后工单
  - `GET /api/admin/after-sales` — 客服分页查看
  - `POST /api/admin/after-sales/{id}/approve` — 通过（退款到钱包）
  - `POST /api/admin/after-sales/{id}/reject` — 拒绝
- `AfterSaleService.java`：审批通过时调 `wallet.refund` + 更新订单状态

**前端**
- `Orders.vue`：已支付订单增加"申请售后"按钮
- `Admin.vue`：增加售后工单 tab，可审批

### P1.4 消息通知中心
**SQL** `sql/13-notification.sql` → `seckill_order.notifications`(
  id PK, user_id, type VARCHAR(20), title VARCHAR(100), content VARCHAR(500),
  related_order_no VARCHAR(64), is_read TINYINT DEFAULT 0, created_at; KEY idx_user_read(user_id,is_read))

**后端**（order-service）
- `NotificationService.java`：`notify(userId, type, title, content, orderNo)` — 落库 + 通过 chat.fanout 推 WebSocket
- `NotificationController.java`：
  - `GET /api/notifications` — 用户通知列表(分页)
  - `PUT /api/notifications/{id}/read` — 标记已读
  - `PUT /api/notifications/read-all` — 全部已读
- 在 `OrderLifecycleService` 的各状态流转处调用 `notificationService.notify(...)`
- 发货/退款时额外发邮件（复用 JavaMailSender）

**前端**
- `App.vue`：顶栏增加通知铃铛(未读数 badge) + 下拉通知列表
- 新增 WebSocket 监听通知频道（复用 useChat 的连接或新建 useNotifications）

---

## P2：营销与用户侧

### P2.1 优惠券/满减
**SQL** `sql/14-coupon.sql` → `seckill_activity.coupons`(
  id PK, name, type TINYINT(0满减/1折扣), min_amount DECIMAL(10,2), discount_amount DECIMAL(10,2),
  start_time, end_time, total_count INT, claimed_count INT DEFAULT 0, status TINYINT DEFAULT 1);
  `seckill_activity.user_coupons`(
  id PK, user_id, coupon_id, status TINYINT(0未使用/1已使用/2已过期), used_order_no, created_at;
  UK(user_id,coupon_id))

**后端**（activity-service）
- `CouponController.java` + `CouponService.java`：
  - `GET /api/coupons` — 可领券列表
  - `POST /api/coupons/{id}/claim` — 领券（Redis 原子扣减 total_count）
  - `GET /api/user/coupons` — 我的优惠券
- order-service `WalletController.pay` 改为可选传 couponId，支付时核销优惠券

**前端**
- 新增 `CouponCenter.vue` 路由 `/coupons`
- `Orders.vue` 支付时增加选券下拉

### P2.2 开抢提醒
**SQL** `sql/15-reminder.sql` → `seckill_activity.seckill_reminders`(
  id PK, user_id, item_id, notify_type TINYINT(0 WebSocket/1 email), created_at;
  UK(user_id,item_id,notify_type))

**后端**（activity-service）
- `ReminderController.java`：`POST /api/seckill/{itemId}/remind` — 订阅提醒
- `@Scheduled` 每分钟扫描即将开始的活动 → WebSocket 推送 + 邮件

**前端**
- `SeckillDetail.vue` / `Home.vue` 未开始商品增加"开抢提醒"按钮

### P2.3 个人中心
**SQL** `sql/16-user-profile.sql` →
`seckill_product.user_favorites`(id PK, user_id, product_id, created_at; UK(user_id,product_id));
`seckill_product.browsing_history`(id PK, user_id, product_id, viewed_at; KEY idx_user_time(user_id,viewed_at))

**后端**
- auth-service `UserController.java`：`PUT /api/user/profile` — 改头像/昵称
- product-service `FavoriteController.java` + `HistoryController.java`：
  - `POST /api/products/{id}/favorite` / `DELETE` / `GET /api/user/favorites`
  - `POST /api/products/{id}/history`（前端访问详情时调） / `GET /api/user/history`

**前端**
- 新增 `Profile.vue` 路由 `/profile`（头像上传 + 昵称编辑 + 收藏/足迹 tab）

### P2.4 运营数据看板
**后端**（order-service）
- `DashboardController.java`：
  - `GET /api/admin/dashboard/gmv` — 总 GMV / 今日 GMV / 7 日趋势
  - `GET /api/admin/dashboard/conversion` — 支付转化率
  - `GET /api/admin/dashboard/hot-items` — 秒杀热度 TOP10
  - `GET /api/admin/dashboard/sales-ranking` — 销量排行

**前端**
- `npm install echarts`
- 新增 `Dashboard.vue` 路由 `/admin/dashboard`（ECharts 图表）

---

## P3：技术加分项

### P3.1 Docker Compose 一键部署
- 每个微服务加 `Dockerfile`（多阶段构建：maven build → openjdk17-runtime）
- 前端加 `Dockerfile`（node build → nginx serve，nginx.conf 反代 /api → gateway）
- `docker-compose.prod.yml`：构建并运行全部服务（auth/product/activity/order x2/gateway + 前端 nginx）
- `.env.example` 补全

### P3.2 Prometheus + Grafana 监控
- 所有微服务 pom.xml 加 `micrometer-registry-prometheus`
- application.yml 暴露 `management.endpoints.web.exposure.include: health,info,prometheus`
- `docker-compose.monitoring.yml`：prometheus + grafana 容器
- `prometheus.yml`：scrape 各服务 808*/actuator/prometheus
- Grafana datasource + 预置 dashboard JSON

---

## 实现顺序
1. **P1 全部**（评价→物流→售后→通知）—— 后端先行，SQL 统一执行
2. **P2 全部**（优惠券→开抢提醒→个人中心→看板）
3. **P3 全部**（Docker→监控）

每完成一个 P 级别：编译打包 → 重启 → 端到端实测 → 前端 build。

## 验证方式
- 每个 SQL 迁移执行后用 `DESC` 确认表结构
- 每个后端端点用 curl + internal-token 直连测试
- 前端 `npm run build` 通过
- 关键流程端到端：评价提交→详情页展示、售后申请→客服审批→退款到钱包、通知推送→前端铃铛亮起
