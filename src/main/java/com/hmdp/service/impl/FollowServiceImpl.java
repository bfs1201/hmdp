package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_FOLLOW_KEY;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    private final FollowMapper followMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final IUserService userService;

    public FollowServiceImpl(FollowMapper followMapper, StringRedisTemplate stringRedisTemplate, IUserService userService) {
        this.followMapper = followMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.userService = userService;
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
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(key, followedId.toString());
            }
        } else {
            // 取关
            isSuccess = remove(
                    new LambdaQueryWrapper<Follow>()
                            .eq(Follow::getUserId, userId)
                            .eq(Follow::getFollowUserId, followedId)
                            .select()
            );
            if (isSuccess) {
                // 不要用delete，delete是直接把键删掉，而我们是想删除键对应的集合里面的一个值
                stringRedisTemplate.opsForSet().remove(key, followedId.toString());
            }
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

    /**
     * 共同关注
     * set实现，intersect取交集
     * 再使用stream流处理结果集
     *
     * @param userId
     * @return
     */
    @Override
    public Result followCommon(Long userId) {
        // 获取当前用户
        Long currentId = UserHolder.getUser().getId();
        String currentKey = BLOG_FOLLOW_KEY + currentId;

        // 求交集
        String userKey = BLOG_FOLLOW_KEY + userId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(currentKey, userKey);
        if (intersect == null || intersect.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        // 解析intersect集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
