# Seckill Spring Cloud 版

这是在保留原 Spring Boot 单体项目的前提下新增的微服务版本。原项目仍位于上级 `seckill` 目录，新服务统一放在 `seckill-cloud`，现有 Vue 前端继续复用并默认访问网关 `http://localhost:8080`。

## 服务划分

| 模块 | 端口 | 数据库 | 职责 |
| --- | ---: | --- | --- |
| `seckill-gateway` | 8080 | 无 | 路由、JWT 校验、管理员鉴权、可信用户头注入 |
| `seckill-auth-service` | 8101 | `seckill_auth` | 用户、验证码、登录、注册、刷新令牌 |
| `seckill-product-service` | 8102 | `seckill_product` | 商品与 MinIO 图片 |
| `seckill-activity-service` | 8103 | `seckill_activity` | 活动、秒杀项、Redis Lua 扣库存 |
| `seckill-order-service` | 8104 | `seckill_order` | RabbitMQ 异步建单、幂等和补偿 |

基础设施包括 MySQL、Redis、RabbitMQ、MinIO 和 Nacos。业务服务之间不能被前端直接访问，必须携带网关注入的内部令牌。为避免与桌面上已有项目冲突，本项目使用 MySQL `3307`、Redis `6380`、RabbitMQ `5673/15673`、MinIO `9010/9011`。

MySQL 容器固定使用 `Asia/Shanghai`（UTC+8），与 Java 服务及活动表中的 `LocalDateTime` 保持一致，避免活动时间错开 8 小时。

## 首次启动

要求：JDK 17、Maven、Node.js 和 Docker Desktop。

```powershell
cd C:\Users\27036\Desktop\seckill\seckill-cloud
docker compose up -d
mvn -DskipTests package
.\scripts\generate-keys.ps1
```

启动脚本会读取 `seckill-cloud/.env` 中的配置；当前 PowerShell 中已设置的环境变量优先级更高。也可以手动覆盖：

```powershell
$env:MAIL_USERNAME = '你的邮箱'
$env:MAIL_PASSWORD = '你的SMTP授权码'
```

随后启动全部后端：

```powershell
.\scripts\start-services.ps1
```

停止这五个 Java 服务（不会停止 Docker 中间件）：

```powershell
.\scripts\stop-services.ps1
```

再启动前端：

```powershell
cd C:\Users\27036\Desktop\seckill\seckill-frontend
npm install
npm run dev
```

访问前端终端输出的地址。API 统一经过 `http://localhost:8080`。

## 数据库初始化

Compose 首次创建 `mysql-data` 卷时会自动按顺序运行 `sql/01-auth.sql` 至 `sql/05-grants.sql`，无需再运行单体项目的 `sql/init.sql`。如果 MySQL 卷已经存在，初始化脚本不会重复执行，此时应手动执行这五个 SQL。`05-grants.sql` 对应 Compose 的默认业务账号 `seckill`；如果自行修改 `MYSQL_USERNAME`，也要同步修改该文件中的授权账号。

`docker compose down -v` 会删除微服务版的全部数据库与中间件数据，只适合确认不需要现有数据时重置；启动脚本不会自动执行这个操作。

## 常用地址

- Nacos：<http://localhost:8848/nacos>
- RabbitMQ 管理台：<http://localhost:15673>（账号和密码来自 `.env`）
- MinIO 控制台：<http://localhost:9011>（账号和密码来自 `.env`）
- Gateway 健康检查：<http://localhost:8080/actuator/health>

本地默认连接参数已经与 `docker-compose.yml` 对齐。生产环境必须通过环境变量替换数据库密码、RabbitMQ/MinIO 密码和 `INTERNAL_TOKEN`，并妥善保存 RSA 私钥。

## 单体版与微服务版切换

前端默认走微服务网关。若临时切回原单体后端：

```powershell
$env:VITE_API_TARGET = 'http://localhost:8081'
npm run dev
```

## 日志

`start-services.ps1` 将各服务日志分别写入 `seckill-cloud/logs`，不会全部混在一个终端疯狂刷屏。排查时只查看对应服务的 `.out.log` 和 `.err.log` 即可。
