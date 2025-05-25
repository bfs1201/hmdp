package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private BlogMapper blogMapper;
    @Resource
    private IUserService userService;

    /**
     * 点赞 + 取消点赞
     * set -> zset
     * zset支持排行榜（sortedSet）
     *
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 2. 判断当前用户是否点赞
        String key = BLOG_LIKED_KEY + id;
//        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
//        if (BooleanUtil.isFalse(isMember)) { // 判断是否为空
        if (score == null) {
            // 未点赞，更新数据库
            LambdaUpdateWrapper<Blog> updateWrapper = new LambdaUpdateWrapper<Blog>()
                    .setSql("liked = liked + 1")
                    .eq(Blog::getId, id);
            int isSuccess = blogMapper.update(updateWrapper);
            if (isSuccess == 1) {
                // 添加到redis缓存
//                stringRedisTemplate.opsForSet().add(key, userId.toString());
//                值为当前毫秒值
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 3. 已点赞，取消点赞，更新数据库
            LambdaUpdateWrapper<Blog> updateWrapper = new LambdaUpdateWrapper<Blog>()
                    .setSql("liked = liked - 1")
                    .eq(Blog::getId, id);
            int isSuccess = blogMapper.update(updateWrapper);
            if (isSuccess == 1) {
                // 数据库更新成功，将用户id从set中移除
//                stringRedisTemplate.opsForSet().remove(key, userId.toString());
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 获取文章详情
     *
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 2.查询blog有关的用户
        queryBlogUser(blog);

        // 3. 获取是否点赞字段
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 获取点赞用户的昵称头像
     *
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 判断当前这篇blog当前用户是否点赞
     *
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long userId = user.getId();
        // 2.判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 点赞前五
     *
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3.根据用户id查询用户 WHERE id IN (5, 1) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOs = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4.返回
        return Result.ok(userDTOs);
    }

    /**
     * 根据点赞数分页查询，最热blog 10条
     *
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户分页查询
        Page<Blog> blogPage = new Page<>(current, SystemConstants.MAX_PAGE_SIZE);
        LambdaQueryWrapper<Blog> queryWrapper = new LambdaQueryWrapper<Blog>()
                .orderByDesc(Blog::getLiked)
                .select();
        Page<Blog> page = blogMapper.selectPage(blogPage, queryWrapper);
//        Page<Blog> page = blogService.query()
//                .orderByDesc("liked")
//                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            // 获取点赞用户的昵称头像
            queryBlogUser(blog);
            // 加上是否点赞字段
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }
}
