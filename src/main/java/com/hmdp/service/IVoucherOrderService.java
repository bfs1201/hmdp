package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IVoucherOrderService extends IService<VoucherOrder> {
    /**
     * 秒杀
     *
     * @param voucherId 优惠券id
     * @return
     */
    Result secKillVoucher(Long voucherId);

    /**
     * 创建订单
     * 用户超卖，加锁，一人一单
     * <p>aop</p>解决事务方法被其他方法调用，事务失效的问题
     *
     * @param voucherOrder 订单信息
     */
    void createVoucherOrder(VoucherOrder voucherOrder);
}
