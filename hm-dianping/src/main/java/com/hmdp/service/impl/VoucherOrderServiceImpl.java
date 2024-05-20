package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import jdk.nashorn.internal.objects.annotations.Constructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    RedissonClient redissonClient;

    @Resource
    ISeckillVoucherService seckillVoucherService;

    @Resource
    RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderTask());
    }
    private class VoucherOrderTask implements Runnable {
        @Override
        public void run() {
            while (true){
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    voucherOrderHandler(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    private void voucherOrderHandler(VoucherOrder voucherOrder) {
        Long userID = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //很关键，不使用.toString().intern()那么每次及时userID值相同，对象也不同，做不到上锁
        //SimpleLock lock = new SimpleLock(stringRedisTemplate,"order:"+userID);

        RLock lock = redissonClient.getLock("lock:order:" + userID);

        //失败不等待
        boolean isLock = lock.tryLock();

        if (!isLock){
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        }finally {
            lock.unlock();
        }
    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
    }

    private VoucherOrderServiceImpl proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), UserHolder.getUser().getId().toString());
        //2.判断是否有购买资格，0代表有，1和2代表没有
        int re = result.intValue();
        if (re == 1){
            return Result.fail("库存不足");
        }
        if (re == 2){
            return Result.fail("用户已经下过单了");
        }
        //3.下单信息保存到队列中
        //3.1 封装信息
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderID = redisIdWorker.nextId("order:");
        voucherOrder.setId(orderID);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        //3.2 获取代理对象
        proxy = (VoucherOrderServiceImpl) AopContext.currentProxy();
        //3.3 放入阻塞队列
        orderTasks.add(voucherOrder);

        return Result.ok(orderID);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠券信息
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //查询活动是否开始 是否结束
//        LocalDateTime beginTime = voucher.getBeginTime();
//        LocalDateTime endTime = voucher.getEndTime();
//        if (beginTime.isAfter(LocalDateTime.now())) {
//            return Result.fail("活动未开始");
//        }
//        if(endTime.isBefore(LocalDateTime.now())){
//            return Result.fail("活动已经结束");
//        }
//        //查询库存是否充足
//        Integer stock = voucher.getStock();
//        if (stock < 1){
//            return Result.fail("库存不足");
//        }
//
//        //根据用户id上锁,悲观锁解决
//        Long userID = UserHolder.getUser().getId();
//        //很关键，不使用.toString().intern()那么每次及时userID值相同，对象也不同，做不到上锁
//        //SimpleLock lock = new SimpleLock(stringRedisTemplate,"order:"+userID);
//
//        RLock lock = redissonClient.getLock("lock:order:" + userID);
//
//        //失败不等待
//        boolean isLock = lock.tryLock();
//
//        if (!isLock){
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            VoucherOrderServiceImpl proxy = (VoucherOrderServiceImpl) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId,userID);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        }finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //查询订单，如果不存在再创建订单
        Long voucherId = voucherOrder.getVoucherId();
        Long userID = voucherOrder.getVoucherId();

        //到数据库中查询库存
        int count = query()
                .eq("user_id", userID)
                .eq("voucher_id", voucherId).count();
        if (count > 0){
            log.error("库存不足");
            return;
        }
        //扣库存
        boolean update = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock",0).update();
        if (!update){
            return;
        }
        //创建订单，保存订单，返回订单id
        save(voucherOrder);
    }
}
