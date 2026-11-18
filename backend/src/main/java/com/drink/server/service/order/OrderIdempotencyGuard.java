package com.drink.server.service.order;

import com.drink.server.common.BusinessException;
import com.drink.server.common.RedisUtil;
import com.drink.server.dto.OrderSubmitDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 下单幂等保护。
 *
 * <p>同一用户 + 同一购物车指纹在 3 秒内只放行一次，拦截连点与客户端重试；
 * Redis 不可用时 fail-open（可用性优先），由 uk_order_no 兜底。
 */
@Slf4j
@Component
public class OrderIdempotencyGuard {

    /** 幂等键存活时间（秒）：覆盖用户连点与客户端重试窗口 */
    private static final long IDEM_TTL_SECONDS = 3;

    private final RedisUtil redisUtil;

    public OrderIdempotencyGuard(RedisUtil redisUtil) {
        this.redisUtil = redisUtil;
    }

    /**
     * 构造幂等键：idem:order:{userId}:{sha256}
     * 指纹按 (drinkId, spec, quantity) 排序后摘要，避免 items 顺序抖动导致幂等失效。
     */
    public String buildKey(Long userId, OrderSubmitDTO dto) {
        List<OrderSubmitDTO.OrderItem> sorted = new ArrayList<>(dto.getItems());
        sorted.sort(Comparator
                .comparing(OrderSubmitDTO.OrderItem::getDrinkId, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(OrderSubmitDTO.OrderItem::getSpec, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(OrderSubmitDTO.OrderItem::getQuantity, Comparator.nullsFirst(Comparator.naturalOrder())));

        StringBuilder sb = new StringBuilder();
        for (OrderSubmitDTO.OrderItem item : sorted) {
            sb.append(item.getDrinkId()).append('|')
                    .append(item.getSpec() == null ? "" : item.getSpec()).append('|')
                    .append(item.getQuantity()).append(';');
        }
        return "idem:order:" + userId + ":" + sha256Hex(sb.toString());
    }

    /**
     * 抢占幂等键。Redis 不可用时放行（fail-open）。
     */
    public boolean acquire(String idemKey) {
        try {
            Boolean first = redisUtil.setIfAbsent(idemKey, "1", IDEM_TTL_SECONDS, TimeUnit.SECONDS);
            return !Boolean.FALSE.equals(first);
        } catch (Exception e) {
            log.warn("幂等键写入失败，本次放行: key={}, cause={}", idemKey, e.getMessage());
            return true;
        }
    }

    /**
     * 业务失败时释放幂等键，避免用户被自己的一次失败请求阻塞 3s。
     */
    public void release(String idemKey) {
        try {
            redisUtil.delete(idemKey);
        } catch (Exception e) {
            log.warn("幂等键释放失败，等待 TTL 自然过期: key={}, cause={}", idemKey, e.getMessage());
        }
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new BusinessException("下单失败，请重试");
        }
    }
}