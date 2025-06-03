package com.hmdp;

import com.hmdp.service.IShopService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.hmdp.mapper")
@SpringBootApplication
public class HmDianPingApplication {
    @Resource
    private IShopService shopService;

    @PostConstruct
    public void init() {
        // 预热逻辑：存储所有商铺信息到 Redis
        for (int i = 1; i <= 14; i++) {
            shopService.saveLogicalExpireToRedis((long) i, 10L);
        }
    }


    public static void main(String[] args) {
        SpringApplication.run(HmDianPingApplication.class, args);
    }

    /**
     * 自定义消息转换器
     * @return
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
