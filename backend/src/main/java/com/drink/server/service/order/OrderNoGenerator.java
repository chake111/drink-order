package com.drink.server.service.order;

import com.drink.server.common.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单号 / 取餐号生成器。
 *
 * <p>正常路径使用 Redis 当日原子序列；Redis 不可用时降级为
 * 「随机种子 + JVM 内单调自增」，保证下单不被 Redis 单点阻断。
 */
@Slf4j
@Component
public class OrderNoGenerator {

    /** 订单号/取餐号共用当日序列的 key 前缀 */
    private static final String ORDER_SEQ_KEY_PREFIX = "order:seq:";

    /** 序列 key 过期时间（小时）：2 天，防止 key 无限增长 */
    private static final long ORDER_SEQ_TTL_HOURS = 48;

    /** Redis 不可用时的降级后缀：随机种子 + JVM 内单调自增（单实例内唯一，多实例靠 uk_order_no 兜底） */
    private static final AtomicLong FALLBACK_SEQ =
            new AtomicLong(ThreadLocalRandom.current().nextInt(1_000_000));

    private final RedisUtil redisUtil;

    public OrderNoGenerator(RedisUtil redisUtil) {
        this.redisUtil = redisUtil;
    }

    /**
     * 取当日序列号：Redis 原子自增（INCR + 首次 EXPIRE 同一脚本）。
     * Redis 不可用时返回 -1，调用方降级为本地自增后缀。
     */
    public long nextDailySeq() {
        String key = ORDER_SEQ_KEY_PREFIX + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        try {
            return redisUtil.incrementWithTtl(key, ORDER_SEQ_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Redis 序列不可用，订单号/取餐号降级为本地自增: key={}, cause={}", key, e.getMessage());
            return -1L;
        }
    }

    /**
     * 生成订单号：年月日时分秒(14) + 6 位后缀 = 20 字符，≤32。
     * 降级后缀取「随机种子 + JVM 内单调自增」而非纯随机，保证同批取餐号不重复。
     */
    public String buildOrderNo(long seq) {
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        long suffix = seq > 0 ? seq : FALLBACK_SEQ.incrementAndGet();
        return dateTime + String.format("%06d", Math.floorMod(suffix, 1_000_000L));
    }

    /**
     * 生成取餐号：正常路径为当日序列 %03d（超过 999 自然进位为 4 位，≤8）；
     * 降级路径取订单号后 3 位，保证与订单号同源、同批不重号。
     */
    public String buildPickupNo(long seq, String orderNo) {
        if (seq > 0) {
            return String.format("%03d", seq);
        }
        return orderNo.substring(orderNo.length() - 3);
    }
}