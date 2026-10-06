-- 固定窗口计数器
-- KEYS[1]: 稳定的限流 key（HASH，存储窗口编号和请求计数）
-- ARGV[1]: 阈值
-- ARGV[2]: 窗口大小（秒）
-- 返回值：1 允许，0 拦截

-- 使用 Redis 时间确定对齐窗口，不在脚本内生成或访问额外 key。
local now = tonumber(redis.call('TIME')[1])
local window = tonumber(ARGV[2])
local windowId = math.floor(now / window)
local previousWindow = redis.call('HGET', KEYS[1], 'window')

if not previousWindow or tonumber(previousWindow) ~= windowId then
    redis.call('HSET', KEYS[1], 'window', windowId, 'count', 0)
end

local current = redis.call('HINCRBY', KEYS[1], 'count', 1)

if current == 1 then
    redis.call('EXPIRE', KEYS[1], window - (now % window) + 1)
end

if current > tonumber(ARGV[1]) then
    return 0
end

return 1
