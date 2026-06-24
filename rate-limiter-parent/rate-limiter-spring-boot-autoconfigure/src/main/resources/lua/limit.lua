-- Redis限流Lua脚本（固定窗口计数器）
-- 原子操作保证高并发下计数准确，避免流量击穿
-- 
-- 入参说明：
-- KEYS[1]: 限流Key（格式：rate_limit:{业务Key}:{窗口起始时间戳}）
-- ARGV[1]: 限流阈值（允许的最大请求次数）
-- ARGV[2]: 时间窗口大小（秒）
--
-- 返回值：
-- 1 - 允许通过（当前计数 <= 阈值）
-- 0 - 拦截请求（当前计数 > 阈值）

-- 构造Redis Key：包含业务标识和窗口时间戳，实现自动过期隔离
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

-- 获取当前计数值（不存在则返回0）
local current = redis.call('GET', key)

-- 判断是否首次请求或窗口已过期
if current == false then
    -- 初始化计数器为1，并设置过期时间（窗口时长+1秒缓冲）
    redis.call('INCR', key)
    redis.call('EXPIRE', key, window + 1)
    return 1
end

-- 转换为数字类型进行比较
local count = tonumber(current)

-- 判断是否超过限流阈值
if count >= limit then
    -- 已超限，返回0拦截请求
    return 0
else
    -- 未超限，计数器+1
    redis.call('INCR', key)
    return 1
end
