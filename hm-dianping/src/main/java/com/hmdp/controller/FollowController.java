package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    IFollowService followService;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") long followUserID, @PathVariable("isFollow") boolean isFollow){
        return followService.follow(followUserID,isFollow);
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") long followUserID){
        return followService.isFollow(followUserID);
    }

    @GetMapping("/common/{id}")
    public Result followCommon(@PathVariable("id") long followUserID){
        return followService.followCommon(followUserID);
    }
}
