-- seckill_with_stream.lua
-- KEYS:
--   KEYS[1] = stockKeyPrefix, e.g. "seckill:stock:"
--   KEYS[2] = userSetKeyPrefix, e.g. "seckill:users:"
--   KEYS[3] = streamKeyPrefix, e.g. "seckill:stream:"
-- ARGV:
--   ARGV[1] = userId
--   ARGV[2] = productId
--   ARGV[3] = bucketIndex
--   ARGV[4] = requestId

local userId = ARGV[1]
local productId = ARGV[2]
local bucketIndex = ARGV[3]
local requestId = ARGV[4]

-- construct keys (keep same pattern as your Java side)
local totalStockKey = KEYS[1] .. productId .. ":total"
local stockBucketKey = KEYS[1] .. productId .. ":bucket_" .. bucketIndex
local userSetKey = KEYS[2] .. productId
local streamKey = KEYS[3] .. productId

-- 返回码说明（调用端请依据业务处理）:
-- 0 = 成功 (已减库存并已写 stream)
-- 1 = 重复购买 (user 已在 set 中)
-- 2 = 总库存已售罄
-- 3 = 当前桶已空 (try another bucket)
-- 99 = 脚本内部错误（建议记录/告警并重试）

-- 1) 检查总库存（如果 totalStockKey 不存在或 <=0，视为售罄）
local totalStockRaw = redis.call("GET", totalStockKey)
local totalStock = nil
if totalStockRaw then
    totalStock = tonumber(totalStockRaw)
end
if (not totalStock or totalStock <= 0) then
    return 2
end

-- 2) 检查是否已购买
if redis.call("SISMEMBER", userSetKey, userId) == 1 then
    return 1
end

-- 3) 扣减桶库存（DECR 在 key 不存在时会创建并返回 -1/-n，注意提前加载桶）
local stock = redis.call("DECR", stockBucketKey)
stock = tonumber(stock)

if (not stock) then
    -- 不应发生：表示 bucketKey 未被初始化或脚本执行异常
    -- 尽量返回错误码，由调用方决定重试/降级
    -- 不尝试手工修复，返回 99
    return 99
end

if (stock < 0) then
    -- 超卖保护：将扣减回滚（将负数加回）
    redis.call("INCR", stockBucketKey)
    return 3
end

-- 4) 减总库存并标记用户
redis.call("DECR", totalStockKey)
redis.call("SADD", userSetKey, userId)

-- 5) 生成 payload 并写入 Redis Stream（作为 outbox）
local payload = cjson.encode({
    requestId = requestId,
    userId = userId,
    productId = productId,
    bucket = bucketIndex,
    ts = tostring(redis.call("TIME")[1])  -- 可选时间戳 (seconds)
})

-- XADD: 将事件追加到 stream（streamKey），条目 field = "payload"
-- 注意：若 XADD 失败会抛出错误（极少见，需在运维层面保障）
redis.call("XADD", streamKey, "*", "payload", payload)

-- 成功
return 0
