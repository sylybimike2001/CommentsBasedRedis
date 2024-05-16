package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import cn.hutool.core.lang.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleLock implements ILock{
    @Resource
    StringRedisTemplate stringRedisTemplate;

    private String name ;
    private static final String KEY_PREFIX = "lock:";

    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    public SimpleLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String poolId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if (threadId.equals(poolId))
            stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
