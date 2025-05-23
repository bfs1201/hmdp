package com.hmdp.interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 从treadLocal中获取用户信息
        UserDTO user = UserHolder.getUser();
        // 未登录，拦截
        if (user == null) {
            response.setStatus(401); // 401是HTTP状态码之一，表示“未授权（Unauthorized）”。
            return false;
        }
        // 已登录，放行
        return true;
    }
}
