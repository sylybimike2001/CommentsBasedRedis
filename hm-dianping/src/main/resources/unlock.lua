---
--- Generated by Luanalysis
--- Created by MyPC.
--- DateTime: 2024/5/16 21:10
---
if(ARGV[1] == redis.call('get',KEYS[1])) then
    return redis.call("del",KEYS[1])
end
return 0