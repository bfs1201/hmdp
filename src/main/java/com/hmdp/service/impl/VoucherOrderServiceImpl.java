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
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

    // 代理对象，防止事务失效
    private IVoucherOrderService proxy;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RabbitTemplate rabbitTemplate;

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

    // 秒杀优化
    public Result secKillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 1. 执行lua脚本
        Long result = null;
        try {
            result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(), userId.toString()
            );
        } catch (Exception e) {
            log.error("lua脚本异常");
            return Result.fail("优惠券不存在");
        }

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
//        // 保存到阻塞队列
//        orderTasks.add(voucherOrder);

        // 将订单信息发送到消息队列
        rabbitTemplate.convertAndSend("hmdp.direct", "voucher.order", voucherOrder);

        // 获取代理对象解决事务方法被其他方法调用，事务失效的问题
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 3. 返回订单id
        return Result.ok(orderId);
    }

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
