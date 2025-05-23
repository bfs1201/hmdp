package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.LOCK_ORDER_KEY;

/**
 * 优惠券秒杀
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private SeckillVoucherServiceImpl secKillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker; // 全局id唯一自增生成器
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        // 加载静态资源
        SECKILL_SCRIPT.setLocation(new ClassPathResource("secKill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 异步处理线程池
    public static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // 代理对象，防止事务失效
    private IVoucherOrderService proxy;
    @Resource
    private RedissonClient redissonClient;

    // 在类初始化之后执行，因为当这个类初始化好了之后，随时都是由可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 异步处理线程池任务
     * 当初始化完成后，会从队列中拿信息
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2. 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常{}", String.valueOf(e));
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 获取用户
        Long userId = voucherOrder.getUserId();
        // 2. 创建锁对象
        RLock redisLock = redissonClient.getLock(LOCK_ORDER_KEY + userId);

        // 3. 尝试获取锁
        boolean isLock = redisLock.tryLock();

        // 4. 判断获取锁是否成功
        if (!isLock) {
            // 获取失败，直接返回失败或者重试
            // 其实前面redis已经判断了超卖，这里有些重复
            log.error("不能重复下单");
            return;
        }
        try {
            // 事务代理，防止失效
            // 注意：有无spring的事务放在threadLocal中，此时的是多线程，事务会失效
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }

    // 创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    // 秒杀优化
    public Result secKillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 1. 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );

        int res = result.intValue();

        // 2. 判断结果
        if (res != 0) {
            // 不为0说明没有购买资格
            return Result.fail(res == 1 ? "库存不足" : "不能重复下单");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单ID
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 用户ID
        voucherOrder.setUserId(userId);
        // 代金券ID
        voucherOrder.setVoucherId(voucherId);
        // 保存到阻塞队列
        orderTasks.add(voucherOrder);
        // 获取代理对象解决事务方法被其他方法调用，事务失效的问题
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 3. 返回订单id
        return Result.ok(orderId);
    }

    // 穿行执行
//    @Override
//    public Result secKillVoucher(Long voucherId) {
//        // 1. 查询优惠券
//        SeckillVoucher voucher = secKillVoucherService.getById(voucherId);
//        // 2. 判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始！");
//        }
//
//        // 3. 判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束！");
//        }
//
//        // 4. 判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足！");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        // 创建锁对象🔒
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//
//        // 获取锁对象
//        boolean isLock = lock.tryLock(120);
//        // 获取失败
//        if (!isLock) {
//            return Result.fail("不允许重复下单！");
//        }
//        try {
//            // 获取代理对象（防止事务失效）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            // 释放锁🔒
//            lock.unlock();
//        }
//    }

    /**
     * 创建订单
     * 用户超卖，加锁，一人一单
     *
     * @param voucherOrder 优惠券信息
     */
    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 子线程不能通过threadLocal获取
//        Long userId = UserHolder.getUser().getId();
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 5.1.查询订单
        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过一次！");
            return;
        }

        // 6.扣减库存
        boolean success = secKillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足！");
            return;
        }

        // 7.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2.用户id
        voucherOrder.setUserId(userId);
        // 7.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 7.返回订单id
//        return Result.ok(orderId);
    }
}


//
//        // 5. 扣减库存
/// /        boolean success = secKillVoucherService.update()
/// /                .setSql("stock= stock -1") //set stock = stock -1
/// /                .eq("voucher_id", voucherId)
/// /                .eq("stock",voucher.getStock())
/// /                .update(); //where id = ？ and stock = ?
//        // CAS
/// /        LambdaUpdateWrapper<SeckillVoucher> updateWrapper = new LambdaUpdateWrapper<SeckillVoucher>()
/// /                .setSql("stock = stock - 1")
/// /                .eq(SeckillVoucher::getVoucherId, voucherId)
/// /                .eq(SeckillVoucher::getStock, voucher.getStock()); // 乐观锁，但是成功率低
//
//        LambdaUpdateWrapper<SeckillVoucher> updateWrapper = new LambdaUpdateWrapper<SeckillVoucher>()
//                .setSql("stock = stock - 1")
//                .eq(SeckillVoucher::getVoucherId, voucherId)
//                .gt(SeckillVoucher::getStock, 0); // 乐观锁
//
//        boolean success = secKillVoucherService.update(updateWrapper);
//
//        if (!success) {
//            return Result.fail("库存不足！");
//        }
//
//        // 6. 创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 6.1 订单ID
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        // 6.2 代金券ID
//        voucherOrder.setVoucherId(voucherId);
//        // 6.3 用户ID
//        Long userId;
//        // 模拟一条用户 ID 用于测试
////        if (UserHolder.getUser() == null) {
////            userId = 1010L;
////        } else {
////            userId = UserHolder.getUser().getId();
////        }
//        userId = UserHolder.getUser().getId();
//        voucherOrder.setUserId(userId);
//        // 插入一条
//        save(voucherOrder);
//
//        // 7. 返回订单ID
//        return Result.ok(orderId);