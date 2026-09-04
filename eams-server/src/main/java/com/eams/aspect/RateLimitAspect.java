package com.eams.aspect;

import com.eams.annotation.RateLimit;
import com.eams.constant.MessageConstant;
import com.eams.constant.RedisConstant;
import com.eams.context.BaseContext;
import com.eams.exception.BaseException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 Redis Lua 脚本的接口限流切面（原子化固定窗口）
 */
@Aspect
@Component
@Slf4j
public class RateLimitAspect {

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    public void init() {
        RATE_LIMIT_SCRIPT.setLocation(new ClassPathResource("rate_limit.lua"));
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    @Before("@annotation(rateLimit)")
    public void rateLimit(RateLimit rateLimit) {
        Long currentId = BaseContext.getCurrentId();
        String suffix = currentId == null ? "anonymous" : String.valueOf(currentId);
        String key = RedisConstant.RATE_LIMIT_KEY + rateLimit.key() + ":" + suffix;

        Long count = stringRedisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                List.of(key),
                String.valueOf(rateLimit.limit()),
                String.valueOf(rateLimit.period())
        );

        if (count != null && count > rateLimit.limit()) {
            log.warn("接口限流触发, key={}, count={}", key, count);
            throw new BaseException(MessageConstant.RATE_LIMIT_EXCEEDED);
        }
    }
}
