package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {
    //    @Override
//    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        // 1. 获取session
//        HttpSession session = request.getSession();
//
//        // 2. 获取session中的用户
//        Object user = session.getAttribute("user");
//
//        // 3. 判断用户是否存在
//        if (user == null) {
//            // 4. 不存在就拦截
//            response.setStatus(401);
//            return false;
//        }
//
//        // 5. 存在就保存用户信息到ThreadLocal
//        UserHolder.saveUser((UserDTO) user);
//
//        // 6. 放行
//        return true;
//    }

    // 因为LoginInterceptor是手动创建new出来的对象，不能交给Spring容器依赖注入，只能手动注入
    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
//            // 未登录，拦截
//            response.setStatus(401);
//            return false;
            return true; // 交给下一层拦截器处理
        }
//        // 2. 获取session中的用户
//        Object user = session.getAttribute("user");
        // 2. 基于token获取redis中的用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        // 3. 判断用户是否存在
//        if (user == null) {
//            // 不存在就拦截
//            response.setStatus(401);
//            return false;
//        }
        if (userMap.isEmpty()) {
            // 不存在就拦截
            response.setStatus(401);
            return false;
        }
        // 4. 将查询到的用户Hash数据转化为DTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        // 5. 存在就保存用户信息到ThreadLocal
//        UserHolder.saveUser((UserDTO) user);
        UserHolder.saveUser(userDTO);

        // 6. 刷新redis中的token的有效期
        stringRedisTemplate.expire(key, LOGIN_USER_TTL * 100, TimeUnit.MINUTES); // 方便调试，这里过期时间设置100倍

        // 7. 放行
        return true;
    }


    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 视图层渲染结束，最后执行
        // 从threadLocal中移除用户信息，防止内存泄漏
        UserHolder.removeUser();
    }
}
