package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;

//import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    private static final ExecutorService CACHE_REBULID = Executors.newFixedThreadPool(10); // 用于缓存重建的线程池，有十个线程

    private boolean tryLock(String key) {
        Boolean absent = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 2, TimeUnit.SECONDS); //这个key是否设置成功
        return BooleanUtil.isTrue(absent);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    public Result queryById(Long id) {
        Shop shop = querywithLogicExpire(id); // 缓存击穿
        if (shop == null)
            return Result.fail("值不存在");
        return Result.ok(shop);
        /*// 解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 7.返回
        return Result.ok(shop);*/
    }

    private Shop queryWithPassThrough(Long id) {   // 缓存穿透
        // redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);


        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) { // 存在
            Shop shop_bean = JSONUtil.toBean(shopJson, Shop.class);
            return shop_bean;
        }
        // 不存在，判断是否为空值
        if (shopJson != null) return null;  // 这里的！=null，其实就是等于""
        // 不存在，数据库进行查询
        Shop shop = getById(id);
        //存在。写进缓存，返回
        if (shop != null) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return shop;
        }
        //不存在，写一个空值到缓存里面去，设置短暂的过期时间
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
        return null;
    }

    public void saveShpoToRedis(Long id, Long expireMinutes) {
//        Shop shop = query().eq("id", id).one();
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(expireMinutes));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    private Shop querywithLogicExpire(Long id) {   // 利用逻辑过期解决
        // 从redis当中读取
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 不存在，返回null
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        // 存在，判断是否逻辑过期
        RedisData bean = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject shopObject = (JSONObject) bean.getData();  // 之前用的泛型，这里直接替代
        Shop shopest = JSONUtil.toBean(shopObject, Shop.class);
        LocalDateTime expireTime = bean.getExpireTime();
//        log.info(String.valueOf(expireTime));
//        log.info(String.valueOf(LocalDateTime.now()));
        // 不过期，直接返回数据
        if (expireTime.isAfter(LocalDateTime.now())) {
            log.info("没有过期");
            return shopest;
        } else {
            // 过期 获得互斥锁
            String key = LOCK_SHOP_KEY + shopest.getId();
            log.info("过期啦");
            boolean flag = tryLock(key);
            // 获取成功
            if (flag) {
                // 开辟线程
                CACHE_REBULID.submit(() -> {
                    try {
                        log.info("获取锁成功");

                        saveShpoToRedis(id, 1L);
                        Thread.sleep(1000);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        log.info("释放锁拉");
                        unLock(key);
                    }
                });

            }

        }

        // 返回数据


        return shopest;
    }

    private Shop queryWithPassMutex(Long id) {   // 利用互斥锁解决缓存击穿
        // redis查询缓存
        try {
            String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);


            // 判断是否存在
            if (StrUtil.isNotBlank(shopJson)) { // 存在
                Shop shop_bean = JSONUtil.toBean(shopJson, Shop.class);
                return shop_bean;
            }
            // 不存在，判断是否为空值
            if (shopJson != null) return null;  // 这里的！=null，其实就是等于""

            // 实现缓存重建
            // 获取互斥锁
            boolean flag = tryLock(LOCK_SHOP_KEY + id);

            log.info(flag + "zcy");
            // 判断是否获取成功
            // 不成功，休眠并重试
            if (!flag) {
                Thread.sleep(50);
                queryWithPassMutex(id);
            }

            // 成功，  查询数据库，并将数据写入redis


            // 不存在，数据库进行查询

            Shop shop = getById(id);
            Thread.sleep(200); // 模拟高并发
            //存在。写进缓存，返回
            if (shop != null) {
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return shop;
            }

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(LOCK_SHOP_KEY + id);
        }
        return null;
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}
