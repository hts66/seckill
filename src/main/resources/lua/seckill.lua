-- ============================================================
-- 秒杀核心 Lua 脚本：原子校验 + 扣库存 + 记录用户
-- KEYS[1] = seckill:stock:{itemId}    库存 key
-- KEYS[2] = seckill:users:{itemId}    已购用户 Set key
-- ARGV[1] = userId                     当前用户ID
-- ARGV[2] = limitPerUser              每人限购数量
--
-- 返回值:
--   1  = 秒杀成功
--  -1  = 库存不足
--  -2  = 已购买过（重复下单）
-- ============================================================

local stockKey = KEYS[1]
local usersKey = KEYS[2]
local userId  = ARGV[1]
local limit   = tonumber(ARGV[2])

-- 1. 检查库存
local stock = redis.call('GET', stockKey)
if not stock or tonumber(stock) <= 0 then
    return -1
end

-- 2. 检查是否已购买（一人一单）
local bought = redis.call('SCARD', usersKey)
-- 简单实现：Set 中是否存在该用户
local already = redis.call('SISMEMBER', usersKey, userId)
if already == 1 then
    return -2
end

-- 3. 扣库存 + 记录用户（原子操作）
redis.call('DECR', stockKey)
redis.call('SADD', usersKey, userId)

return 1
