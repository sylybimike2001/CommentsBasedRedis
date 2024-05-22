package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.service.impl.FollowServiceImpl.getUserSetInfo;
import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    IUserService userService;

    @Resource
    IFollowService followService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryByID(Integer id) {
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("博客不存在");
        }
        //给blog添加额外的信息
        queryBlogUser(blog);
        //查询是否点过赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        Blog blog = getById(id);
        Long userId = UserHolder.getUser().getId();
//        Boolean isMember = stringRedisTemplate.opsForSet().isMember("blog:liked:" + id, userId.toString());
        Double isMember = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + id, userId.toString());
        if(isMember != null){
            boolean isSuccess = this.update()
                    .setSql("liked = liked - 1").eq("id", id).update();
            System.out.println("点赞-1");
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_KEY + id, userId.toString());
            }
            //点赞减一
        }else{
            boolean isSuccess = this.update()
                    .setSql("liked = liked + 1").eq("id", id).update();
            System.out.println("点赞+1");
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY + id, userId.toString(),System.currentTimeMillis());
            }
            //点赞加一
        }
        return Result.ok(blog);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        return getUserSetInfo(top5, userService);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
            //select * from tb_follower where user_id = user.getID()
            return Result.fail("博文发布失败！");
        }
        // 查询博主的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        System.out.println(follows);
        // 把博客推送到所有粉丝
        for(Follow follow : follows){
            Long userId = follow.getUserId();
            // 保存博客id到redis
            stringRedisTemplate.opsForZSet().add("feed:" + userId, blog.getId().toString(), System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog);
    }

    @Override
    public Result queryBlogOfFollow(Long lastID, Integer offset) {
        UserDTO user = UserHolder.getUser();
        String key = "feed:" + user.getId();
        //1.查询自己收件箱的所有博文
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, lastID, offset, 2);
        if(typedTuples == null|| typedTuples.isEmpty()){
            return Result.ok();
        }
        //.reverseRangeByScore(key, 0, lastID, offset, 2);
        //2.数据解析和处理,确定offset lastID
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for(ZSetOperations.TypedTuple<String> typedTuple : typedTuples){
            ids.add(Long.valueOf(typedTuple.getValue()));
            long time = typedTuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }
        os = minTime == lastID? os + offset : os;
        //3.根据blogid查询blog
        String joinIds = StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + joinIds + ")").list();
        //4.封装
        for (Blog blog : blogs) {
            //给blog添加额外的信息
            queryBlogUser(blog);
            //查询是否点过赞
            isBlogLiked(blog);
        }
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null){
            return;
        }
        Long userId = UserHolder.getUser().getId();
        Double isMember = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(), userId.toString());
        blog.setIsLike(isMember != null);
    }
}
