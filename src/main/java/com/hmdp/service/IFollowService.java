package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IFollowService extends IService<Follow> {

    /**
     * 关注
     * @param followedId 被关注用户的id
     * @param isFollow 是否关注
     * @return
     */
    Result follow(Long followedId, Boolean isFollow);

    /**
     * 获取是否关注
     * @param authorId 作者id
     * @return 是否关注
     */
    Result isFollow(Long authorId);
}
