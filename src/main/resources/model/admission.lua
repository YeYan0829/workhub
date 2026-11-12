-- One persistent hash per real provider quota, plus bounded-lived permit receipts.
-- All keys share a Redis Cluster hash tag. Never initialize during an application call.
local state, receipt = KEYS[1], KEYS[2]
local op, config = ARGV[1], ARGV[2]
local tm = redis.call('TIME')
local now = tonumber(tm[1])*1000 + math.floor(tonumber(tm[2])/1000)
if op == 'initialize' then
  if redis.call('EXISTS', state) == 1 then return {'EXISTS', now, 0} end
  local recovery = tonumber(ARGV[5])
  local rate, burst = tonumber(ARGV[3]), tonumber(ARGV[4])
  if not recovery or not rate or not burst or rate <= 0 or burst < 1 or recovery < math.ceil(1000*burst/rate) then return {'INVALID', now, 0} end
  redis.call('HSET', state, 'config', config, 'generation', ARGV[6], 'rate', rate, 'burst', burst,
    'tokens', 0, 'at', now+recovery, 'cooldown', now+recovery, 'circuit', 'CLOSED', 'epoch', 0, 'failures', 0, 'blocked', 0)
  return {'INITIALIZED', now, now+recovery}
end
if redis.call('EXISTS', state) == 0 then return {'MISSING', now, 0} end
local stored = redis.call('HGET', state, 'config')
local version, rate, burst = string.match(config, '^team%-v1|(%d+)|([%d.eE+-]+)|(%d+)$')
if stored ~= config then
  local oldVersion = string.match(stored, '^team%-v1|(%d+)|')
  -- Old in-flight receipts still report cooldown/failure into the same quota domain.
  if not (op == 'outcome' and version and oldVersion) then
    if op ~= 'acquire' or not oldVersion or not version or tonumber(version) <= tonumber(oldVersion)
      or not tonumber(rate) or tonumber(rate) < 0.001 or tonumber(rate) > 10000 or tonumber(burst) < 1 or tonumber(burst) > 10000 then
      return {'CONFIG', now, 0}
    end
    -- Monotonic configuration migration never grants a fresh bucket. Preserve circuit,
    -- generation, outstanding receipts, recovery horizon and supplier cooldown.
    redis.call('HSET', state, 'config', config, 'rate', rate, 'burst', burst, 'tokens', 0,
      'at', math.max(now, tonumber(redis.call('HGET', state, 'at'))))
  end
end
local shared = redis.call('HMGET', state, 'generation', 'epoch', 'circuit', 'cooldown')
local generation, epoch, circuit, cooldown = shared[1], tonumber(shared[2]), shared[3], tonumber(shared[4])
local function open()
  epoch = epoch+1
  redis.call('HSET', state, 'circuit', 'OPEN', 'epoch', epoch, 'openUntil', now+30000, 'failures', 0,
    'tokens', 0, 'at', now+30000)
end
if op == 'outcome' then
  local r = redis.call('GET', receipt)
  if not r then return {'IGNORED', now, 0} end
  redis.call('DEL', receipt) -- a physical response counts at most once
  local g, e = string.match(r, '^(.-):(%d+)$')
  if g ~= generation then return {'IGNORED', now, 0} end
  local status, delay = tonumber(ARGV[3]), tonumber(ARGV[4])
  if status == 429 then
    if delay < 0 then redis.call('HSET', state, 'blocked', 1) -- malformed hint: explicit operator recovery
    else cooldown = math.max(cooldown, now+delay);redis.call('HSET', state, 'cooldown', cooldown, 'tokens', 0, 'at', cooldown) end
  end
  if tonumber(e) ~= epoch then return {'STALE', now, cooldown} end
  if circuit == 'HALF_OPEN' then
    if status >= 200 and status < 300 then
      redis.call('HSET', state, 'circuit', 'CLOSED', 'epoch', epoch+1, 'failures', 0)
    else open() end
  elseif circuit == 'CLOSED' then
    if status == 502 or status == 503 or status == 504 then
      if redis.call('HINCRBY', state, 'failures', 1) >= 5 then open() end
    elseif status >= 200 and status ~= 429 then redis.call('HSET', state, 'failures', 0) end
  end
  return {'RECORDED', now, cooldown}
end
if op ~= 'acquire' then return {'INVALID', now, 0} end
if redis.call('HGET', state, 'blocked') == '1' then return {'RECOVERY_REQUIRED', now, 0} end
if circuit == 'HALF_OPEN' then
  if now >= tonumber(redis.call('HGET', state, 'probeUntil')) then open();return {'OPEN', now, 30000} end
  return {'PROBE_BUSY', now, 0}
end
if circuit == 'OPEN' and now < tonumber(redis.call('HGET', state, 'openUntil')) then return {'OPEN', now, 0} end
if now < cooldown then return {'COOLDOWN', now, cooldown-now} end
local bucket = redis.call('HMGET', state, 'rate', 'burst', 'at', 'tokens')
local rate, burst, at, tokens = tonumber(bucket[1]), tonumber(bucket[2]), tonumber(bucket[3]), tonumber(bucket[4])
tokens = math.min(burst, tokens+math.max(0,now-at)*rate/1000)
if tokens < 1 then
  redis.call('HSET', state, 'tokens', tokens, 'at', math.max(at,now))
  return {'RATE', now, math.ceil((1-tokens)*1000/rate)}
end
-- Receipt expires after max HTTP duration + permit lifetime + settling allowance.
redis.call('SET', receipt, generation..':'..epoch, 'PX', 90000)
redis.call('HSET', state, 'tokens', tokens-1, 'at', now)
if circuit == 'OPEN' then redis.call('HSET', state, 'circuit', 'HALF_OPEN', 'probeUntil', now+65000) end
return {'PERMIT', now, epoch}
