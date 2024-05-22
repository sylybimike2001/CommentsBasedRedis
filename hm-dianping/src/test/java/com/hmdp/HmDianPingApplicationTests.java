package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Follow;
import com.hmdp.entity.Shop;
import com.hmdp.service.IFollowService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    ShopServiceImpl shopService;

    @Resource
    CacheClient cacheClient;

    @Resource
    RedisIdWorker redisIdWorker;

    @Resource
    IFollowService followService;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void saveShop() throws InterruptedException {
        Shop shop = shopService.getById(1);
        cacheClient.setWithExpireTime(CACHE_SHOP_KEY + 1L,shop,10L, TimeUnit.SECONDS);
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

    @Test
    public void generateId() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task =()->{
            for(int i = 0 ; i < 100 ; i++){
                long voucher = redisIdWorker.nextId("voucher");
                System.out.println("id:" + voucher);
            }
            countDownLatch.countDown();
        };
        long be = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        countDownLatch.await();
        long en = System.currentTimeMillis();
        System.out.println("time:" + (en - be));
    }

    @Test
    void testInterSection(){
        List<Follow> follows = followService.query().eq("follow_user_id", 2L).list();
        System.out.println(follows);
    }

    @Test
    void loadShopData(){
        //search
        List<Shop> list = shopService.list();
        //classify by id
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            List<Shop> shops = entry.getValue();
            String key = "shop:geo:" + typeId;
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
            for (Shop shop : shops) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),new Point(shop.getX(),shop.getY())
                        )
                );
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }

    }
}
