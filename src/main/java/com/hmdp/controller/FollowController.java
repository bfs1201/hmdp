package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;

    /**
     * 关注
     * @param followedId 被关注用户的id
     * @param isFollow 是否关注
     * @return
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followedId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followedId, isFollow);
    }

    /**
     * 获取是否关注
     * @param authorId 作者id
     * @return 是否关注
     */
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long authorId) {
        return followService.isFollow(authorId);
    }
}
