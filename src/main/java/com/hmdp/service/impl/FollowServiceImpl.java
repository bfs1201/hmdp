package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import static com.hmdp.utils.RedisConstants.BLOG_FOLLOW_KEY;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    private final FollowMapper followMapper;

    public FollowServiceImpl(FollowMapper followMapper) {
        this.followMapper = followMapper;
    }

    /**
     * 关注
     * @param followedId 被关注用户的id
     * @param isFollow 是否关注
     * @return
     */
    @Override
    public Result follow(Long followedId, Boolean isFollow) {
        // 1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_FOLLOW_KEY + userId;

        // 2. 判断是 关注 还是 取关
        boolean isSuccess;
        if (isFollow) {
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followedId);
            isSuccess = save(follow);
        } else {
            // 取关
            isSuccess = remove(
                    new LambdaQueryWrapper<Follow>()
                            .eq(Follow::getUserId, userId)
                            .eq(Follow::getFollowUserId, followedId)
                            .select()
            );
        }
        return Result.ok(isSuccess);
    }

    /**
     * 获取是否关注
     * @param authorId 作者id
     * @return 是否关注
     */
    @Override
    public Result isFollow(Long authorId) {
        Long userId = UserHolder.getUser().getId();

        // 查询是否关注当前作者
        LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, authorId)
                .select();
        Long count = followMapper.selectCount(queryWrapper);
        return Result.ok(count > 0);
    }
}
