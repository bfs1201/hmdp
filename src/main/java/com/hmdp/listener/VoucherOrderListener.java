package com.hmdp.listener;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class VoucherOrderListener {
    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * RabbitMQ异步处理订单
     *
     * @param voucherOrder
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "voucher.order"),
            exchange = @Exchange(name = "hmdp.direct", type = "direct"),
            key = "voucher.order"))
    public void HandleVoucherOrder(VoucherOrder voucherOrder) {
        voucherOrderService.createVoucherOrder(voucherOrder);
    }
}
