package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    IUserService userService;

//    public FollowServiceImpl(StringRedisTemplate stringRedisTemplate) {
//        this.stringRedisTemplate = stringRedisTemplate;
//    }

    @Override
    public Result follow(Long followUserID, boolean isFollow) {
        UserDTO user = UserHolder.getUser();
        if (isFollow){
            Follow follow = new Follow();
            follow.setUserId(user.getId());
            follow.setFollowUserId(followUserID);
            boolean saved = save(follow);
            if (saved){
                stringRedisTemplate.opsForSet().add("follows:" + user.getId().toString(), followUserID.toString());
            }
        }else{
            QueryWrapper<Follow> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("user_id", user.getId())
                    .eq("follow_user_id", followUserID);
            boolean removed = remove(queryWrapper);
            if (removed){
                stringRedisTemplate.opsForSet().remove("follows:" + user.getId().toString(), followUserID.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserID) {
        UserDTO user = UserHolder.getUser();
        Integer count = query().eq("user_id", user.getId()).eq("follow_user_id", followUserID).count();
        System.out.println(count);
        if (count <= 0){
            return Result.ok(false);
        }
        return Result.ok(true);
    }

    @Override
    public Result followCommon(Long followUserID) {
        String keyPrefix = "follows:";
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(keyPrefix + followUserID.toString(), keyPrefix + UserHolder.getUser().getId().toString());
        return getUserSetInfo(intersect, userService);
    }

    static Result getUserSetInfo(Set<String> intersect, IUserService userService) {
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        String joinIds = StrUtil.join(",",ids);
        List<User> users = userService.query().in("id", ids).last("ORDER BY FIELD(id," + joinIds + ")").list();
        List<UserDTO> userDTOs = users.stream().map(user -> BeanUtil.copyProperties(user,UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOs);
    }
}
