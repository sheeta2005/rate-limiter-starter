-- 滑动窗口（基于 ZSET，时间基准为 Redis 服务端时钟）
-- KEYS[1]: 限流 key（ZSET）
-- ARGV[1]: 阈值
-- ARGV[2]: 窗口大小（秒）
-- ARGV[3]: 本次请求的唯一成员标识（由客户端生成，保证不覆盖）
-- 返回值：1 允许，0 拦截

local now = redis.call('TIME')
local nowUs = now[1] * 1000000 + now[2]
local windowUs = tonumber(ARGV[2]) * 1000000
local boundary = nowUs - windowUs

redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, boundary)

local count = redis.call('ZCARD', KEYS[1])

if count >= tonumber(ARGV[1]) then
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]) + 1)
    return 0
end

redis.call('ZADD', KEYS[1], nowUs, ARGV[3])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]) + 1)
return 1
