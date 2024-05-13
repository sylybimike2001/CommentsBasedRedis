package com.hmdp.service.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    StringRedisTemplate stringRedisTemplate;


    @Override
    public Result queryShopType() {
        //先在redis中查询
        String shopTypeJson = stringRedisTemplate.opsForValue().get(SHOP_TYPE_KEY);
        //如果查到，直接返回
        if(StrUtil.isNotBlank(shopTypeJson)){
            List<ShopType> shopTyps = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTyps);
        }
        //如果没查到，在数据库中查找
        List<ShopType> shopTyps = query().orderByAsc("sort").list();

        //数据库中查找，找不到报错
        if(shopTyps == null){
            return Result.fail("不存在");
        }
        //找到，缓存到redis
        System.out.println(JSONUtil.toJsonStr(shopTyps));
        stringRedisTemplate.opsForValue().set(SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopTyps));
        return Result.ok(shopTyps);
    }
}
