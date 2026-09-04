package com.eams.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis 分布式锁（基于 SET NX EX + Lua 原子释放）
 * 用于缓存击穿防护，轻量无外部依赖
 */
@Slf4j
@Component
public class RedisLock {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    /**
     * 尝试获取分布式锁
     * @param key        锁的 Redis key
     * @param ttlSeconds 锁自动过期时间（秒）
     * @return 锁标识 value（用于释放锁时校验），获取失败返回 null
     */
    public String tryLock(String key, long ttlSeconds) {
        String value = UUID.randomUUID().toString();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, value, ttlSeconds, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(success)) {
            log.debug("获取分布式锁成功: key={}", key);
            return value;
        }
        log.debug("获取分布式锁失败: key={}", key);
        return null;
    }

    /**
     * 释放分布式锁（Lua 脚本保证原子性：仅当 value 匹配时才删除）
     * @param key   锁的 Redis key
     * @param value 获取锁时返回的标识值
     */
    public void unlock(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(UNLOCK_LUA, Long.class);
            Long result = stringRedisTemplate.execute(script, Collections.singletonList(key), value);
            if (Long.valueOf(1).equals(result)) {
                log.debug("释放分布式锁成功: key={}", key);
            } else {
                log.warn("释放分布式锁失败（可能已自动过期或被其他线程持有）: key={}", key);
            }
        } catch (Exception e) {
            log.error("释放分布式锁异常: key={}", key, e);
        }
    }
}
