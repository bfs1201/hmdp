package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ShopMapper shopMapper;

    @Transactional
    @Override
    public Result queryById(Long id) {
//        String key = CACHE_SHOP_KEY + id;
//        // 1. 从redis中查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        // 2. 判断是否存在
//        if (StrUtil.isNotBlank(shopJson)) {
//            // 3. 存在，直接返回
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class, true);
//            return Result.ok(shop);
//        }
//
//        // 解决缓存击穿问题，命中了预设的空字符串，空字符串 != null
//        if (shopJson != null) {
//            // 返回错误信息
//            return Result.fail("店铺不存在！");
//        }
//
//        // 4. 不存在，根据id到数据库查询商铺信息
//        Shop shop = getById(id);
//
//        // 5. 数据库中也不存在，返回错误信息
//        if (shop == null) {
//            // 解决缓存击穿问题
//            // 写入一个空字符串，设置一个比较短的过期时间
//            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return Result.fail("店铺不存在！");
//        }
//
//        // 6. 数据库中存在，写入redis
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
//        // 设置过期时间，超时剔除
//        stringRedisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 这里使用互斥锁解决缓存击穿问题
//        Shop shop = queryWithMutex(id);

        // 这里采用逻辑过期解决缓存击穿问题
        Shop shop = queryWithLogicalExpire(id);

        // 7. 返回
        if (shop == null) {
            return Result.fail("商品信息不存在！");
        }
        return Result.ok(shop);
    }

    @Transactional // 添加事务，保证更新数据库和删除缓存操作的原子性
    @Override
    public Result updateShopById(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空！");
        }
        // 1. 更新数据库
        updateById(shop);

        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    // 使用redis实现互斥锁
    private boolean tryLock(String lockKey) {
        // 实际上这里使用的是redis中的setnx
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        // 自动拆箱可能会空指针，所以这里使用工具类
        return BooleanUtil.isTrue(flag);
    }

    // 释放锁
    private void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }

    /**
     * 互斥锁解决缓存击穿问题
     *
     * @param id
     * @return
     */
    private Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class, true);
        }

        // 解决缓存击穿问题，命中了预设的空字符串，空字符串 != null，这里的缓存击穿是用户访问一个不存在的id引起的
        if (shopJson != null) { // shopJson == ""
            // 返回错误信息
//            return Result.fail("店铺不存在！");
            return null;
        }

        // 4. 实现缓存重构
        // 4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            // 获取互斥锁
            boolean isLock = tryLock(lockKey);
            // 4.2 判断是否获取成功
            if (!isLock) {
                // 失败，休眠一段时间之后重试（递归）
                Thread.sleep(20);
                return queryWithMutex(id);
            }
            // 4.4 成功，根据id查询数据库

            // 使用mp实现根据id到数据库查询商铺信息
            shop = getById(id);

            // 5. 数据库中也不存在，返回错误信息
            if (shop == null) {
                // 解决缓存击穿问题
                // 写入一个空字符串，设置一个比较短的过期时间
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                //            return Result.fail("店铺不存在！");
                return null;
            }

            // 6. 数据库中存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
            // 设置过期时间，超时剔除
            stringRedisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7. 释放互斥锁
            unlock(lockKey);
        }
        return shop;
    }

    // 创建一个线程池，保证线程安全
    private static final ExecutorService cacheRebuildExecutor = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期解决缓存击穿问题
     *
     * @param id
     * @return
     */
    private Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 从redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3. blank直接返回
            return null;
        }

        // 4. 命中，需要将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未过期，直接返回商铺信息
            return shop;
        }

        // 5.2 已过期，下面进行缓存重建
        // 6. 缓存重建
        // 6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        // 6.2 判断获取锁是否成功
        if (isLock) {
            cacheRebuildExecutor.submit(() -> {
                try {
                    // 6.3 获取成功，重建缓存
                    this.saveLogicalExpireToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 6.4 释放锁
                    unlock(lockKey);
                }
            });
        }

        // 7. 返回商品信息
        return shop;
    }

    /**
     * 将带有逻辑过期时间的商铺信息存入redis
     *
     * @param id
     * @param expireTime
     */
    public void saveLogicalExpireToRedis(Long id, Long expireTime) {
        // 1. mp查询店铺数据
        Shop shop = getById(id);
        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));

        // 3. 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 预热所有商铺信息到redis
     *
     * @param expireTime
     */
    @Override
    public void preheatAllShopInfo(long expireTime) {
        List<Shop> shops = shopMapper.selectList(new LambdaQueryWrapper<>());
        RedisData redisData = new RedisData();
        for (Shop shop : shops) {
            redisData.setData(shop);
            redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + shop.getId(), JSONUtil.toJsonStr(redisData));
        }
    }
}
