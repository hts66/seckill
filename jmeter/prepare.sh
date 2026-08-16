#!/bin/bash
# ============================================================
# 压测前准备：生成用户 → 注册 → 预登录获取 Token
# 用法: bash prepare.sh [用户数量] [stock数量]
# 默认: bash prepare.sh 5000 5000
# ============================================================

USER_COUNT="${1:-5000}"
STOCK="${2:-${USER_COUNT}}"
HOST="${HOST:-http://localhost:8080}"
CSV="users.csv"
TOKENS="tokens.csv"
ITEM_ID="${ITEM_ID:-6}"

echo "=============================================="
echo "  压测准备: ${USER_COUNT} 用户, 库存 ${STOCK}"
echo "=============================================="

# ===== 步骤1: 批量注册用户 =====
echo ""
echo ">>> [1/3] 注册 ${USER_COUNT} 个用户..."
echo "email,password" > "${CSV}"

OK_COUNT=0
for i in $(seq 1 ${USER_COUNT}); do
  EMAIL="test${i}@test.com"
  PASSWORD="123456"

  RESP=$(curl -s -X POST "${HOST}/api/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"username\":\"用户${i}\"}" 2>/dev/null)

  echo "$EMAIL,$PASSWORD" >> "${CSV}"

  if echo "$RESP" | grep -q '"code":200'; then
    OK_COUNT=$((OK_COUNT + 1))
  fi

  # 进度显示
  if [ $((i % 500)) -eq 0 ]; then
    echo "  进度: ${i}/${USER_COUNT}"
  fi
done
echo "  完成! 注册/已存在: ${OK_COUNT} 个, CSV: $(wc -l < ${CSV}) 行"

# ===== 步骤2: 预登录获取 Token =====
echo ""
echo ">>> [2/3] 预登录获取 Token..."
echo "token" > "${TOKENS}"

TOKEN_COUNT=0
tail -n +2 "${CSV}" | while IFS=',' read -r email password; do
  email=$(echo "$email" | tr -d '\r\n')
  password=$(echo "$password" | tr -d '\r\n')

  RESP=$(curl -s -X POST "${HOST}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${email}\",\"password\":\"${password}\"}" 2>/dev/null)

  TOKEN=$(echo "$RESP" | grep -o '"token":"[^"]*"' | head -1 | sed 's/"token":"//;s/"//')

  if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
    echo "TOKEN_ERROR" >> "${TOKENS}"
  else
    echo "${TOKEN}" >> "${TOKENS}"
    TOKEN_COUNT=$((TOKEN_COUNT + 1))
  fi
done
echo "  完成! Token 数量: $(tail -n +2 ${TOKENS} | wc -l)"

# ===== 步骤3: 设置 Redis 库存 =====
echo ""
echo ">>> [3/3] 设置 Redis 库存..."
# 查找当前活动的 item (状态=1)
if [ -n "$ITEM_ID" ]; then
  redis-cli SET "seckill:stock:${ITEM_ID}" "${STOCK}" 2>/dev/null
  echo "  完成! item_id=${ITEM_ID} 库存设为 ${STOCK}"
else
  echo "  [警告] 未检查 Redis key，请确认 seckill:stock:${ITEM_ID} 已预热"
  echo "  请手动设置: redis-cli SET seckill:stock:${ITEM_ID} ${STOCK}"
fi

echo ""
echo "=============================================="
echo "  准备完成!"
echo "  users.csv:  $(wc -l < ${CSV}) 行"
echo "  tokens.csv: $(wc -l < ${TOKENS}) 行"
echo "  下一步: bash staircase_test.sh"
echo "=============================================="
