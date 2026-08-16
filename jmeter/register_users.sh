#!/bin/bash
# 批量注册1000个测试用户并生成CSV
# 用法: bash register_users.sh

HOST="${HOST:-http://localhost:8080}"
CSV_FILE="users.csv"
COUNT=1000

echo "email,password" > "$CSV_FILE"

for i in $(seq 1 $COUNT); do
  EMAIL="test${i}@test.com"
  PASSWORD="123456"

  RESP=$(curl -s -X POST "$HOST/api/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"username\":\"用户${i}\"}")

  CODE=$(echo "$RESP" | grep -o '"code":[0-9]*' | head -1 | cut -d: -f2)

  if [ "$CODE" = "200" ]; then
    echo "$EMAIL,$PASSWORD" >> "$CSV_FILE"
    echo "[$i/$COUNT] OK  $EMAIL"
  else
    echo "[$i/$COUNT] SKIP $EMAIL (already exists or error)"
    # 即使注册失败也加入CSV（可能已存在），登录仍可用
    echo "$EMAIL,$PASSWORD" >> "$CSV_FILE"
  fi
done

echo ""
echo "Done! CSV: $CSV_FILE ($(wc -l < $CSV_FILE) lines)"
