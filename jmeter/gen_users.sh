#!/bin/bash
# 批量注册测试用户并生成 CSV
# 先用小数量(20)测试正确性，然后改为1000

HOST="${HOST:-http://localhost:8080}"
CSV="users.csv"
COUNT=${1:-1000}  # 默认1000，可传参指定

echo "email,password" > "$CSV"

for i in $(seq 1 $COUNT); do
  EMAIL="test${i}@test.com"
  curl -s -X POST "$HOST/api/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"123456\",\"username\":\"user${i}\"}" \
    | grep -q '"code":200' \
    && echo "$EMAIL,123456" >> "$CSV" \
    && echo "[$i/$COUNT] OK"
done

echo "Done! Generated $(wc -l < $CSV) users"
