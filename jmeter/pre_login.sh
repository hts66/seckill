#!/bin/bash
# 预登录脚本：从 users.csv 读取用户，逐一登录获取 token，生成 tokens.csv
# 用法: bash pre_login.sh [HOST] [PORT]
# 默认: bash pre_login.sh localhost 8080

HOST="${1:-localhost}"
PORT="${2:-8080}"
BASE_URL="http://${HOST}:${PORT}"
INPUT="users.csv"
OUTPUT="tokens.csv"

echo "======== 预登录生成 Token ========"
echo "从 ${INPUT} 读取用户..."
echo "写入 ${OUTPUT} ..."
echo ""

# 写表头
echo "token" > "${OUTPUT}"

# 跳过表头，逐行登录
COUNT=0
tail -n +2 "${INPUT}" | while IFS=',' read -r email password; do
  # 去掉可能的回车符
  email=$(echo "$email" | tr -d '\r\n')
  password=$(echo "$password" | tr -d '\r\n')

  # 调登录接口
  RESP=$(curl -s -X POST "${BASE_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${email}\",\"password\":\"${password}\"}" 2>/dev/null)

  # 提取 token（假设返回格式: {"code":200,"data":{"token":"xxx",...}}）
  TOKEN=$(echo "$RESP" | grep -o '"token":"[^"]*"' | head -1 | sed 's/"token":"//' | sed 's/"//')

  if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
    echo "  [失败] ${email} — 响应: $(echo $RESP | head -c 100)"
    echo "TOKEN_ERROR" >> "${OUTPUT}"
  else
    echo "  [成功] ${email} → token: ${TOKEN:0:30}..."
    echo "${TOKEN}" >> "${OUTPUT}"
    COUNT=$((COUNT+1))
  fi

  # 避免打爆服务，间隔 50ms
  sleep 0.05
done

TOTAL=$(wc -l < "${OUTPUT}")
echo ""
echo "======== 完成: 生成 $((TOTAL-1)) 个 Token ========"
