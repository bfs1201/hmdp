package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IBlogService extends IService<Blog> {

    /**
     * 点赞 + 取消点赞
     *
     * @param id
     * @return
     */
    Result likeBlog(Long id);

    /**
     * 获取文章详情
     *
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 点赞前五
     *
     * @param id
     * @return
     */
    Result queryBlogLikes(Long id);

    /**
     * 根据点赞数分页查询，最热blog 10条
     * @param current
     * @return
     */
    Result queryHotBlog(Integer current);
}
