package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // session实现
//    @Override
//    public Result sendCode(String phone, HttpSession session) {
//        // 1. 校验手机号
//        if (RegexUtils.isPhoneInvalid(phone)) {
//            // 2. 如果不符合，返回错误信息
//            return Result.fail("手机号格式错误！");
//        }
//
//        // 3. 格式符合，生成验证码
//        String code = RandomUtil.randomNumbers(6);
//
//        // 4. 保存验证码到session
//        session.setAttribute("code", code);
//
//        // 5. 发送验证码（模拟实现，本应调用服务）
//        log.debug("发动验证码成功，验证码{}", code);
//        // 6. 200
//        return Result.ok();
//    }

    /**
     * 发送验证码
     *
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        // 3. 格式符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

//        // 4. 保存验证码到session
//        session.setAttribute("code", code);

        // 4. 保存验证码到redis(key, value, ttl, TimeUnit)
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5. 发送验证码（模拟实现，本应调用服务）
        log.debug("发动验证码成功，验证码: {}", code);
        // 6. 200
        return Result.ok();
    }

    //    @Override
//    public Result login(LoginFormDTO loginForm, HttpSession session) {
//        // 1. 校验手机号
//        String phone = loginForm.getPhone();
//
//        // 2. 如果不符合，返回错误信息
//        if (RegexUtils.isPhoneInvalid(phone)) {
//            return Result.fail("手机号格式错误！");
//        }
//        // 3. 校验验证码
//        Object cacheCode = session.getAttribute("code");
//        String code = loginForm.getCode();
//        // 3.1 session过期，或者输入验证码错误
//        if (cacheCode == null || !cacheCode.toString().equals(code)) {
//            return Result.fail("验证码错误！");
//        }
//
//        // 3.2 验证码一致，根据手机号查询用户
//        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<User>()
//                .eq(User::getPhone, phone)
//                .select();
//        User user = userMapper.selectOne(queryWrapper);
//
//        // 4. 判断用户是否存在
//        // 4.1 用户不存在，创建新用户
//        if (user == null) {
//            user = createUserWithPhone(phone);
//        }
//
//        // 5. 保存用户信息到session中
//        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
//        session.setAttribute("user", userDTO);
//
//        // 6. 200
//        return Result.ok();
//    }

    /**
     * 登录
     *
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();

        // 2. 如果不符合，返回错误信息
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
//        // 3. 校验验证码
//        Object cacheCode = session.getAttribute("code");
//        // 3.1 session过期，或者输入验证码错误
//        if (cacheCode == null || !cacheCode.toString().equals(code)) {
//            return Result.fail("验证码错误！");
//        }

        String code = loginForm.getCode();
        // 3. 从redis中获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误！");
        }

        // 3.2 验证码一致，根据手机号查询用户
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<User>()
                .eq(User::getPhone, phone)
                .select();
        User user = userMapper.selectOne(queryWrapper);

        // 4. 判断用户是否存在
        // 4.1 用户不存在，创建新用户
        if (user == null) {
            user = createUserWithPhone(phone);
        }

//        // 5. 保存用户信息到session中
//        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
//        session.setAttribute("user", userDTO);

        // 5. 保存用户信息到redis中
        // 5.1 随机生成token（使用UUID），作为登陆令牌
        String token = UUID.randomUUID().toString(true); // UUID带不带‘-’
        // 5.2 将User对象转换为Map存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        // stringRedisTemplate存储字段必须是String，所以要将id（Long）转换为String
        // 手动将id字段，即键id对应的值转化为String
        userMap.replace("id", userDTO.getId().toString());
        // 另一种比较高级的方法是指定转化方法，自动将所有值转化为String
//        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
//                CopyOptions.create() // 用于创建一个CopyOptions实例，以便后续对转换过程进行配置
//                        .setIgnoreNullValue(true) // Bean到Map的转换过程中，忽略掉所有null值的字段
//                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())); // Lambda表达式，它将每一个字段的值都转换为字符串形式
        // 5.3 将转换的userMap存储到redis
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 5.4 设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL * 100, TimeUnit.MINUTES); // 方便调试，这里过期时间设置100倍
        // 6. 200->返回token给前端
        return Result.ok(token);
    }

    /**
     * 登出功能
     * 删除redis中的token对应的key
     *
     * @param token
     */
    @Override
    public void logout(String token) {
        String key = LOGIN_USER_KEY + token;
        stringRedisTemplate.delete(key);
    }

    private User createUserWithPhone(String phone) {
        // 1. 创建一个用户，给手机号，给一个随机昵称
        User user = User.builder()
                .phone(phone)
                .nickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10))
                .build();

        // 2. 保存用户
        save(user); // 插入数据库

        // 3. 返回
        return user;
    }
}
