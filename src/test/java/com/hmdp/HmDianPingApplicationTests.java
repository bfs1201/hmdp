package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
public class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    // redisson客户端
    @Resource
    private RedissonClient redissonClient;

    @Test
    void testRedisson() throws InterruptedException {
        // 获取可重入锁，指定锁名称
        RLock lock = redissonClient.getLock("anyLock");
        // 尝试获取锁，参数分别是：最大等待时间（解决不可重试问题），锁自动释放时间，时间单位
        boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);
        // 判断获取锁成功
        if (isLock) {
            try {
                System.out.println("执行业务");
            } finally {
                // 释放锁
                lock.unlock();
            }
        }
    }

    /**
     * 热点Key预热
     * 预热所有商店信息
     */
    @Test
    public void testLogicalExpire() {
        // 存储9个商铺信息
        for (int i = 1; i <= 14; i++) {
            shopService.saveLogicalExpireToRedis((long) i, 10L);
        }
    }

    @Resource
    private RedisIdWorker redisIdWorker;

    // 创建一个500个线程的线程池
    private final ExecutorService executorService = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        // 一种同步辅助，它允许一个或多个线程等待，直到在其他线程中执行的一组操作完成
        // CountDownLatch 是Java并发包提供的工具类，用于线程间的同步。它允许一个或多个线程等待其他线程完成操作。
        // 在这里，CountDownLatch被初始化为300，这意味着主线程需要等待300次countDown()操作才能继续执行。
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();

        for (int i = 0; i < 300; i++) {
            // 300次countDown
            executorService.submit(task);
        }
        // latch.await() 方法会阻塞主线程，直到CountDownLatch的计数器为0，即300个countDown()操作都被调用。
        // 这确保了主线程在所有子线程完成任务之前不会继续执行。
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
        // 这段代码的主要功能是：
        // 创建一个CountDownLatch，用于等待多个线程完成任务后再继续执行。
        // 定义一个任务，该任务会生成并打印100个ID。
        // 使用一个固定大小为500的线程池并发地运行300个任务。
        // 通过CountDownLatch等待所有任务完成后，计算整个任务执行所用的时间，并打印出来。
        // 通过这种方式，代码测试了RedisIdWorker类的nextId方法在高并发场景下的性能和生成ID的稳定性。
    }


    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成1000个token
     * 并且将token写入redis
     *
     * @throws Exception
     */
    @Test
    public void generateToken() throws Exception {
        // 数据库查询1000个用户的信息
        List<User> list = userService.list(new QueryWrapper<User>().last("limit 1000"));
        // 创建字符输入流准备写入token到文件
        BufferedWriter br = new BufferedWriter(new FileWriter("C:\\Users\\bfs\\Desktop\\Redis\\实战篇\\Tokens.txt"));
        for (User user : list) {
            // 随机生成token作为登录令牌
            String token = UUID.randomUUID().toString(true);
            // 将User对象转为HashMap存储到Redis中
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create() // 用于创建一个CopyOptions实例，以便后续对转换过程进行配置
                            .setIgnoreNullValue(true) // 忽略null值
                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())); // 将所有键值对的值转化为String
            //保存用户信息到Redis中
            String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
            //写入token到文件
            br.write(token);
            br.newLine();
            br.flush();
        }
    }
}
