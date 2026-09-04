package com.eams.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.eams.constant.MessageConstant;
import com.eams.constant.RedisConstant;
import com.eams.constant.StatusConstant;
import com.eams.dto.AssetCategoryDTO;
import com.eams.dto.AssetCategoryPageQueryDTO;
import com.eams.entity.AssetCategory;
import com.eams.exception.BaseException;
import com.eams.json.JacksonObjectMapper;
import com.eams.lock.RedisLock;
import com.eams.mapper.AssetCategoryMapper;
import com.eams.mapper.AssetMapper;
import com.eams.result.PageResult;
import com.eams.service.AssetCategoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 资产分类服务：查询启用分类列表时使用 Redis 缓存（1 小时过期），增删改后清理缓存
 */
@Service
@Slf4j
public class AssetCategoryServiceImpl implements AssetCategoryService {

    @Autowired
    private AssetCategoryMapper assetCategoryMapper;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JacksonObjectMapper jacksonObjectMapper;

    @Autowired
    private Cache<String, String> categoryCache;

    @Autowired
    private RedisLock redisLock;

    private static final String CATEGORY_LOCK_KEY = "lock:cache:assetCategory:list";

    private static final long LOCK_TTL_SECONDS = 3;

    private static final int RETRY_MAX = 20;

    private static final long RETRY_SLEEP_MS = 50;

    /**
     * 新增分类
     */
    @Override
    public void add(AssetCategoryDTO assetCategoryDTO) {
        AssetCategory category = new AssetCategory();
        BeanUtils.copyProperties(assetCategoryDTO, category);
        category.setStatus(StatusConstant.ENABLE);
        assetCategoryMapper.insert(category);
        evictCache();
    }

    /**
     * 分类分页查询
     */
    @Override
    public PageResult pageQuery(AssetCategoryPageQueryDTO assetCategoryPageQueryDTO) {
        PageHelper.startPage(assetCategoryPageQueryDTO.getPage(), assetCategoryPageQueryDTO.getPageSize());
        Page<AssetCategory> page = assetCategoryMapper.pageQuery(assetCategoryPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 根据 id 删除分类（分类下存在资产则不允许删除）
     */
    @Override
    public void deleteById(Long id) {
        Integer count = assetMapper.countByCategoryId(id);
        if (count != null && count > 0) {
            throw new BaseException(MessageConstant.CATEGORY_BE_RELATED_BY_ASSET);
        }
        assetCategoryMapper.deleteById(id);
        evictCache();
    }

    /**
     * 修改分类
     */
    @Override
    public void update(AssetCategoryDTO assetCategoryDTO) {
        AssetCategory category = new AssetCategory();
        BeanUtils.copyProperties(assetCategoryDTO, category);
        assetCategoryMapper.update(category);
        evictCache();
    }

    /**
     * 启用/禁用分类状态
     */
    @Override
    public void startOrStop(Integer status, Long id) {
        AssetCategory category = AssetCategory.builder()
                .status(status)
                .id(id)
                .build();
        assetCategoryMapper.update(category);
        evictCache();
    }

    /**
     * 查询启用状态的全部分类：两级缓存（Caffeine + Redis）+ 分布式锁防缓存击穿
     *
     * <p>流程：L1 Caffeine  →  L2 Redis  →  分布式锁 + 双重检测  →  DB</p>
     */
    @Override
    public List<AssetCategory> list() {
        // L1: Caffeine 本地缓存
        List<AssetCategory> result = readFromL1();
        if (result != null) {
            return result;
        }

        // L2: Redis 分布式缓存
        result = readFromL2();
        if (result != null) {
            return result;
        }

        // 两级缓存均未命中 → 分布式锁防击穿
        String lockValue = redisLock.tryLock(CATEGORY_LOCK_KEY, LOCK_TTL_SECONDS);
        if (lockValue != null) {
            // 获取到锁，执行双重检测
            try {
                // 双重检测：等锁期间可能已有线程回填了 Redis
                result = readFromL2();
                if (result != null) {
                    return result;
                }

                // 确实为空，查库并回填
                return loadFromDbAndFillCache();
            } finally {
                redisLock.unlock(CATEGORY_LOCK_KEY, lockValue);
            }
        }

        // 未获取到锁：自旋等待，重试读取缓存
        for (int i = 0; i < RETRY_MAX; i++) {
            try {
                Thread.sleep(RETRY_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            result = readFromL1();
            if (result != null) {
                return result;
            }
            result = readFromL2();
            if (result != null) {
                return result;
            }
        }

        // 自旋超时，降级查库（兜底，防止死等）
        log.warn("缓存击穿自旋超时（{}次），降级查库", RETRY_MAX);
        return loadFromDbAndFillCache();
    }

    // ========== 私有辅助方法 ==========

    /**
     * 从 Caffeine 一级缓存读取
     */
    private List<AssetCategory> readFromL1() {
        String cached = categoryCache.getIfPresent(RedisConstant.CATEGORY_CACHE_KEY);
        if (cached != null) {
            try {
                return jacksonObjectMapper.readValue(cached, new TypeReference<List<AssetCategory>>() {});
            } catch (Exception e) {
                log.warn("解析Caffeine缓存失败: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * 从 Redis 二级缓存读取，命中后回填 Caffeine
     */
    private List<AssetCategory> readFromL2() {
        String cached = null;
        try {
            cached = stringRedisTemplate.opsForValue().get(RedisConstant.CATEGORY_CACHE_KEY);
        } catch (Exception e) {
            log.warn("Redis不可用，跳过: {}", e.getMessage());
        }
        if (cached != null && !cached.isEmpty()) {
            categoryCache.put(RedisConstant.CATEGORY_CACHE_KEY, cached);
            try {
                return jacksonObjectMapper.readValue(cached, new TypeReference<List<AssetCategory>>() {});
            } catch (Exception e) {
                log.warn("解析Redis缓存失败，回源数据库: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * 查数据库并回填两级缓存
     */
    private List<AssetCategory> loadFromDbAndFillCache() {
        List<AssetCategory> list = assetCategoryMapper.list();
        String json;
        try {
            json = jacksonObjectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("序列化分类列表失败: {}", e.getMessage());
            return list;
        }

        categoryCache.put(RedisConstant.CATEGORY_CACHE_KEY, json);
        try {
            stringRedisTemplate.opsForValue().set(
                    RedisConstant.CATEGORY_CACHE_KEY,
                    json,
                    RedisConstant.CATEGORY_CACHE_TTL,
                    TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入Redis缓存失败: {}", e.getMessage());
        }
        return list;
    }

    /**
     * 清理分类缓存：延迟双删策略
     * 先删 Redis，再删 Caffeine，最后异步延迟 500ms 再删一次 Redis，
     * 防止并发请求在间隙中将旧数据写回缓存
     */
    private void evictCache() {
        // 第一次删 Redis（先删 Redis，避免旧数据从 Redis 回填 Caffeine）
        try {
            stringRedisTemplate.delete(RedisConstant.CATEGORY_CACHE_KEY);
        } catch (Exception e) {
            log.warn("清理Redis缓存失败: {}", e.getMessage());
        }

        // 删 Caffeine
        categoryCache.invalidate(RedisConstant.CATEGORY_CACHE_KEY);

        // 异步延迟双删：500ms 后再删一次 Redis，覆盖并发写入的脏数据
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(500);
                stringRedisTemplate.delete(RedisConstant.CATEGORY_CACHE_KEY);
                log.debug("延迟双删完成");
            } catch (Exception e) {
                log.warn("延迟双删失败: {}", e.getMessage());
            }
        });
    }
}
