package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Resource
    ISeckillVoucherService seckillVoucherService;

    @Resource
    RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //查询活动是否开始 是否结束
        LocalDateTime beginTime = voucher.getBeginTime();
        LocalDateTime endTime = voucher.getEndTime();
        if (beginTime.isAfter(LocalDateTime.now())) {
            return Result.fail("活动未开始");
        }
        if(endTime.isBefore(LocalDateTime.now())){
            return Result.fail("活动已经结束");
        }
        //查询库存是否充足
        Integer stock = voucher.getStock();
        if (stock < 1){
            return Result.fail("库存不足");
        }

        //根据用户id上锁,悲观锁解决
        Long userID = UserHolder.getUser().getId();
        //很关键，不使用.toString().intern()那么每次及时userID值相同，对象也不同，做不到上锁
        SimpleLock lock = new SimpleLock(stringRedisTemplate,"order:"+userID);
        boolean isLock = lock.tryLock(1200L);
        if (!isLock){
            return Result.fail("不允许重复下单");
        }
        try {
            VoucherOrderServiceImpl proxy = (VoucherOrderServiceImpl) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId,userID);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        }finally {
            lock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId, Long userID) {
        //查询订单，如果不存在再创建订单
        int count = query()
                .eq("user_id", userID)
                .eq("voucher_id", voucherId).count();
        if (count > 0){
            return Result.fail("用户已经购买过该商品");
        }
        //扣库存
        boolean update = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock",0).update();
        if (!update){
            return Result.fail("库存不足");
        }
        //创建订单，保存订单，返回订单id
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderID = redisIdWorker.nextId("order:");
        voucherOrder.setId(orderID);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userID);
        //当前实现类是order的实现类，会调用保存order的mybatis方法
        save(voucherOrder);
        return Result.ok(orderID);
    }
}
