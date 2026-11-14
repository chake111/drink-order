package com.drink.server.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Redis 工具类（基于 StringRedisTemplate）
 */
@Slf4j
@Component
public class RedisUtil {

    private final StringRedisTemplate redisTemplate;

    /**
     * INCR 与 EXPIRE 原子执行：仅首次自增时设置过期，避免进程中途退出留下永不过期的 key
     */
    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local v = redis.call('INCR', KEYS[1])\n" +
                    "if v == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n" +
                    "return v", Long.class);

    public RedisUtil(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 设置字符串键值
     */
    public void set(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 设置字符串键值（带过期时间）
     */
    public void set(String key, String value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    /**
     * 获取字符串值
     */
    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 删除键
     */
    public Boolean delete(String key) {
        return redisTemplate.delete(key);
    }

    /**
     * 判断键是否存在
     */
    public Boolean hasKey(String key) {
        return redisTemplate.hasKey(key);
    }

    /**
     * 原子自增并设置过期时间（仅首次自增时设置，后续不重置 TTL）
     *
     * @return 自增后的值；脚本返回空时为 0
     */
    public long incrementWithTtl(String key, long timeout, TimeUnit unit) {
        Long value = redisTemplate.execute(INCR_WITH_TTL,
                Collections.singletonList(key), String.valueOf(unit.toSeconds(timeout)));
        return value == null ? 0L : value;
    }
}
