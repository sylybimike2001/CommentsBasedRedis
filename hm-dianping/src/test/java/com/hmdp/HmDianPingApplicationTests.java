package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    ShopServiceImpl shopService;

    @Resource
    CacheClient cacheClient;

    @Test
    public void saveShop() throws InterruptedException {
        Shop shop = shopService.getById(2);
        cacheClient.setWithExpireTime(CACHE_SHOP_KEY + 2L,shop,10L, TimeUnit.SECONDS);
    }

    @Test
    public void whatsgoing() throws InterruptedException {
        String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + 2L);
        System.out.println(json);
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        System.out.println(redisData);
        Object data = redisData.getData();
        System.out.println(data);
        Shop bean = BeanUtil.toBean(data, Shop.class);
        System.out.println(bean);
    }
}
