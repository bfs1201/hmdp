package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 全局id唯一自增生成器
 */
@Component
public class RedisIdWorker {
    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final long BEGIN_TIMESTAMP = 1640995200L; // 使用main方法生成的起始时间的时间戳
    public static final long COUNT_BITS = 32;

    /**
     * 生成全局唯一自增id
     *
     * @param keyPrefix 前缀
     * @return id
     */
    public long nextId(String keyPrefix) {
        // 1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. 生成序列号
        // 2.1 获取当前日期，精确到天
        String formatDate = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

        // 2.2 自增长
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + formatDate);

        // 3. 拼接并返回
        return timestamp << COUNT_BITS | count;
    }

    /**
     * 生成一个时间戳
     *
     * @param args
     */
    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0);
        // time对象调用了toEpochSecond方法，并传入ZoneOffset.UTC作为参数。这个方法会计算从1970年1月1日00:00 UTC到time所表示的时间点之间的秒数
        long epochSecond = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(epochSecond);
    }
}
