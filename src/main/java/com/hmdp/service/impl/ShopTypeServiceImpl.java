package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.netty.util.internal.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private ShopTypeMapper shopTypeMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 店铺类型
     *
     * @return
     */
    @Override
    public Result queryList() {
        String key = CACHE_SHOP_TYPE_KEY;
        // 1. 从redis中取商店类别列表，0，-1表示查全部
        List<String> range = stringRedisTemplate.opsForList().range(key, 0, -1);

        // 2. 判断缓存是否命中，命中就直接返回
//        List<ShopType> shopTypes = new ArrayList<>();
        if (range != null && !range.isEmpty()) {
//            for (String typeString : range) {
//                shopTypes.add(JSONUtil.toBean(typeString, ShopType.class));
//            }
//            return shopTypes;
            List<ShopType> shopTypes = range.stream()
                    .map(typeString -> JSONUtil.toBean(typeString, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(shopTypes);
        }

        // 3. 缓存未命中，mp查询商城类别列表，注意简化写法
//        LambdaQueryWrapper<ShopType> queryWrapper = new LambdaQueryWrapper<ShopType>()
//                .orderByDesc(ShopType::getSort)
//                .select();
//        List<ShopType> shopTypes = shopTypeMapper.selectList(queryWrapper);
        List<ShopType> shopTypes = lambdaQuery().orderByAsc(ShopType::getSort).list();
        if (shopTypes == null || shopTypes.isEmpty()) {
            return Result.fail("商城分类为空！");
        }

        // 4. 将商城类别列表转化为String列表，再批量存储在redis中
//        shopTypes.forEach(shopType -> {
//            Map<String, Object> typesMap = BeanUtil.beanToMap(shopType);
//            typesMap.replace("id", shopType.getId().toString());
//            typesMap.replace("sort", shopType.getSort().toString());
//            stringRedisTemplate.opsForList().leftPush(key, JSONUtil.toJsonStr(typesMap));
//        });
        List<String> typesStringList = shopTypes.stream()
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList());
        // 批量存储
        stringRedisTemplate.opsForList().rightPushAll(key, typesStringList);
        // 设置过期时间，过期剔除
        stringRedisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return Result.ok(shopTypes);
    }
}
