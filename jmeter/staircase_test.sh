#!/bin/bash
# ============================================================
# 阶梯加压测试脚本
# 从低到高逐步增加并发，找到系统瓶颈点
# 用法: bash staircase_test.sh
# ============================================================

HOST="${1:-localhost}"
PORT="${2:-8080}"
JMX="seckill-stress.jmx"
RESULT_DIR="stress_results"
mkdir -p "${RESULT_DIR}"

# 阶梯并发数配置（可根据需要修改）
# 格式: "线程数:ramp-up秒数"
LEVELS=(
  "500:5"
  "1000:10"
  "2000:15"
  "3000:20"
  "5000:30"
  "8000:40"
  "10000:50"
)

echo "=============================================="
echo "  秒杀系统 - 阶梯加压测试"
echo "  目标: http://${HOST}:${PORT}"
echo "  起始时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "=============================================="
echo ""
printf "%-10s %-8s %-10s %-12s %-10s %-10s %-8s\n" \
  "并发数" "总请求" "QPS" "平均响应" "P99" "错误率" "结果"
echo "----------------------------------------------------------------------"

PREV_RESULT="pass"

for LEVEL in "${LEVELS[@]}"; do
  THREADS="${LEVEL%%:*}"
  RAMPUP="${LEVEL##*:}"
  JTL="${RESULT_DIR}/result_t${THREADS}.jtl"
  REPORT="${RESULT_DIR}/report_t${THREADS}"

  # 如果上一步已经失败，跳过更高并发
  if [ "$PREV_RESULT" = "fail" ]; then
    printf "%-10s %-8s %-10s %-12s %-10s %-10s %-8s\n" \
      "$THREADS" "-" "-" "-" "-" "-" "⏭ 跳过"
    continue
  fi

  echo ""
  echo ">>> 开始测试: ${THREADS} 并发 (ramp-up ${RAMPUP}s) ..."

  # 清理旧结果
  rm -f "${JTL}" 2>/dev/null
  rm -rf "${REPORT}" 2>/dev/null

  # 重置 Redis 库存（如果 Redis 有预热脚本的话）
  # redis-cli SET "seckill:stock:8" 1000 2>/dev/null

  # 运行 JMeter
  jmeter -n \
    -t "${JMX}" \
    -Jthreads="${THREADS}" \
    -Jrampup="${RAMPUP}" \
    -Jloops=1 \
    -l "${JTL}" \
    -e -o "${REPORT}" \
    > /dev/null 2>&1

  if [ ! -f "${JTL}" ]; then
    echo "  [错误] JMeter 未生成结果文件，跳过"
    PREV_RESULT="fail"
    continue
  fi

  # 解析结果
  TOTAL=$(grep -v "^timeStamp" "${JTL}" | wc -l)
  if [ "$TOTAL" -eq 0 ]; then
    echo "  [错误] 结果文件为空，跳过"
    PREV_RESULT="fail"
    continue
  fi

  # 统计数据
  ERRORS=$(grep -v "^timeStamp" "${JTL}" | awk -F',' '$4 != "200" && $4 != "" {count++} END {print count+0}')

  # 只分析 "2. 执行秒杀" 步骤
  SECKILL_LINES=$(grep "2. 执行秒杀" "${JTL}")
  SECKILL_COUNT=$(echo "$SECKILL_LINES" | wc -l)

  if [ "$SECKILL_COUNT" -gt 0 ]; then
    # 平均响应时间
    AVG=$(echo "$SECKILL_LINES" | awk -F',' '{sum+=$2; count++} END {if(count>0) printf "%.0f", sum/count; else print 0}')
    # P99
    P99=$(echo "$SECKILL_LINES" | awk -F',' '{print $2}' | sort -n | awk -v total="$SECKILL_COUNT" '
      {a[NR]=$1} END {
        p99_idx=int(total*0.99);
        if(p99_idx<1) p99_idx=1;
        printf "%.0f", a[p99_idx]
      }')
    # QPS = 秒杀请求数 / 测试持续时间
    START_TS=$(echo "$SECKILL_LINES" | head -1 | awk -F',' '{print $1}')
    END_TS=$(echo "$SECKILL_LINES" | tail -1 | awk -F',' '{print $1}')
    DURATION=$(( (END_TS - START_TS) / 1000 ))
    if [ "$DURATION" -le 0 ]; then DURATION=1; fi
    QPS=$(awk "BEGIN {printf \"%.0f\", ${SECKILL_COUNT}/${DURATION}}")
  else
    AVG=0; P99=0; QPS=0
  fi

  ERR_RATE=$(awk "BEGIN {printf \"%.1f%%\", ($ERRORS/${TOTAL})*100}")

  # 判断结果
  RESULT="✅ 通过"
  if [ "$ERRORS" -gt 0 ]; then
    RESULT="⚠️  有错误"
    PREV_RESULT="warn"
  fi
  # 如果错误率 > 5%，判定为失败
  ERR_PCT=$(awk "BEGIN {printf \"%.0f\", ($ERRORS/${TOTAL})*100}")
  if [ "$ERR_PCT" -gt 5 ]; then
    RESULT="❌ 失败(错误>5%%)"
    PREV_RESULT="fail"
  fi
  # 如果 P99 > 10000ms，也判定性能不可接受
  if [ "$P99" -gt 10000 ] 2>/dev/null; then
    RESULT="❌ 失败(P99>10s)"
    PREV_RESULT="fail"
  fi

  printf "%-10s %-8s %-10s %-12s %-10s %-10s %-8s\n" \
    "${THREADS}" "${TOTAL}" "${QPS}/s" "${AVG}ms" "${P99}ms" "${ERR_RATE}" "${RESULT}"
done

echo ""
echo "=============================================="
echo "  测试完成: $(date '+%Y-%m-%d %H:%M:%S')"
echo "  详细报告: ${RESULT_DIR}/report_t*/index.html"
echo "=============================================="
