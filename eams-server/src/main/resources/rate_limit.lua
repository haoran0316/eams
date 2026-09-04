local key = KEYS[1]
local limit = tonumber(ARGV[1])
local period = tonumber(ARGV[2])

local count = redis.call('INCR', key)
if count == 1 then
    redis.call('EXPIRE', key, period)
end
return count
