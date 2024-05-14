package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
public class CacheClient {

    private static final ExecutorService CACHE_REBUID_EXECUTOR = Executors.newFixedThreadPool(10);
    @Resource
    StringRedisTemplate stringRedisTemplate;

    //set 简单的保存功能
    public void set(String key, String value,Long time,TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key,value,time,timeUnit);
    }

    //set 带有逻辑过期字段的保存功能
    public void setWithExpireTime(String key, Object value, Long expireTime, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(expireTime)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //get 解决缓存穿透问题的get方法，缓存穿透：请求的数据在redis和数据库中都不存在
    public <R,ID> R getWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallBack,Long expireTime, TimeUnit timeUnit) {
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        System.out.println(json);
        //如果不是Blank，有Null和空字符串两种情况
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        if (json != null){
            return null;
        }
        R r = dbFallBack.apply(id);
        if (r == null){
            stringRedisTemplate.opsForValue().set(keyPrefix + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        this.set(keyPrefix + id,JSONUtil.toJsonStr(r),expireTime,timeUnit);
        return r;
    }

    //get
    public <R,ID> R getWithLogicExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallBack,Long time, TimeUnit timeUnit) {
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        //模拟一定命中，因此未命中先返回null
        if(StrUtil.isBlank(json)){
            return null;
        }
        //1.反序列化
        RedisData data = JSONUtil.toBean(json, RedisData.class);

        //2.判断是否过期
        LocalDateTime expireTime = data.getExpireTime();
        R r = BeanUtil.toBean(data.getData(),type);
        //2.1如果不过期，直接返回对象
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        //2.2如果过期，尝试获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean lock = tryLock(lockKey);
        //2.2.1 如果获取锁成功,开启线程，返回过期对象
        if(lock){
            CACHE_REBUID_EXECUTOR.submit(()->{
                try {
                    R r1 = dbFallBack.apply(id);
                    setWithExpireTime(keyPrefix + id,r1,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        //2.2.2 如果获取不成功，返回过期对象
        return r;
    }


    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "temp", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
