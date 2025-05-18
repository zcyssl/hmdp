package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jodd.util.CollectionUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.converter.json.GsonBuilderUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_TYPESHOP_KEY;

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
    private StringRedisTemplate redisTemplate;
    private static final Gson gson = new GsonBuilder().create();

    @Override
    public Result queryTypeList() {
        // redis查询商铺类型列表
        List<String> shopRange = redisTemplate.opsForList().range(CACHE_TYPESHOP_KEY, 0, -1);// 查询到的是一个json字符串
// 存在，直接返回
        if (!CollectionUtils.isEmpty(shopRange)) {
            // 首先要转换为Bean
            List<ShopType> collect = shopRange.stream().map(item -> JSONUtil.toBean(item, ShopType.class))
                    .sorted(Comparator.comparing(ShopType::getSort))
                    .collect(Collectors.toList());
            return Result.ok(collect);
        }
        // 不存在
        //查询数据库
        List<ShopType> list = lambdaQuery().orderByAsc(ShopType::getSort).list();
        //存在 写进redis，返回
        if (!CollectionUtils.isEmpty(list)) {
            // 转换为json
            List<String> collect = list.stream().sorted(Comparator.comparingInt(ShopType::getSort))
                    .map(JSONUtil::toJsonStr)
                    .collect(Collectors.toList());
            redisTemplate.opsForList().rightPushAll(CACHE_TYPESHOP_KEY,collect);
            return Result.ok(collect);
        }
        // 不存在，数据报错！
        return Result.fail("数据错误");
    }
}
