# Docker deployment

The Compose stack contains the Vue/Nginx frontend, Gateway, four Java business
services, the Python AI service, MySQL, Redis, RabbitMQ, MinIO, and Nacos.

## Prerequisites

- Docker Desktop or Docker Engine with Compose v2
- RSA files in `keys/private.pem` and `keys/public.pem`
- A DeepSeek API key in `.env` or the Windows User environment

For production, copy `.env.example` to `.env` and fill every required
credential, `INTERNAL_TOKEN`, `DEEPSEEK_API_KEY`, and `MINIO_PUBLIC_URL`.
`MINIO_PUBLIC_URL` must be reachable by users' browsers, not only by containers.

## Build and start

When Docker Hub is reachable:

```powershell
.\scripts\docker-up.ps1 -Build
```

When Docker Hub is unavailable:

```powershell
.\scripts\docker-up.ps1 -Build -DockerRegistry docker.1ms.run
```

After images have been built, normal startup does not rebuild them:

```powershell
.\scripts\docker-up.ps1
```

Open <http://localhost/>. Gateway remains available at
<http://localhost:8080>, and the AI health endpoint is at
<http://localhost:8200/health>.

## Stop

```powershell
.\scripts\docker-down.ps1
```

This preserves all named volumes. Do not use `docker compose down -v` unless
you intentionally want to delete databases, Redis data, RabbitMQ data, and
MinIO images.

## Useful diagnostics

```powershell
docker compose ps
docker compose logs -f seckill-gateway
docker compose logs -f ai-service
```

SQL files under `sql/` run only when the MySQL volume is created for the first
time. Apply later schema migrations explicitly to an existing database volume.
