package com.eams.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存配置：作为一级缓存，Redis 作为二级缓存
 * 用于热点数据（如资产分类列表）的高频读取，减少 Redis 和 DB 压力
 */
@Configuration
public class CaffeineCacheConfig {

    @Bean
    public Cache<String, String> categoryCache() {
        return Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }
}
