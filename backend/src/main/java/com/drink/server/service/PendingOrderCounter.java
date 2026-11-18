package com.drink.server.service;

import com.drink.server.config.OrderWebSocketHandler;
import com.drink.server.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 待接单数内存计数器。
 *
 * <p>广播路径只读内存值，不再为每次状态迁移打一条 COUNT(*)。计数由四条状态迁移维护：
 * 下单 +1、接单 -1、拒单 -1、用户取消 -1；完成/取餐不改变待接单数。
 *
 * <p>跨实例一致性靠 Redis Pub/Sub 广播「绝对计数」而非增量，消息天然幂等；
 * 发布方自身也走订阅回调落内存，避免同一条变更被推送两次。
 * 每分钟一次向 DB 校准兜底，用 {@code SET NX} 抢校准权，避免多实例同时回源。
 */
@Slf4j
@Service
public class PendingOrderCounter implements ApplicationRunner {

    /** 跨实例广播频道 */
    public static final String CHANNEL = "drink:order:pending";

    /** 校准权：TTL 55s < 校准周期 60s，保证每个周期最多一个实例回源 DB */
    private static final String CALIBRATE_LOCK_KEY = "calib:order:pending";
    private static final long CALIBRATE_LOCK_TTL_SECONDS = 55L;

    private final AtomicLong count = new AtomicLong();

    /** 实例标识，仅用于日志定位消息来源 */
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    private final OrderMapper orderMapper;
    private final StringRedisTemplate redisTemplate;
    private final OrderWebSocketHandler webSocketHandler;

    public PendingOrderCounter(OrderMapper orderMapper,
                               StringRedisTemplate redisTemplate,
                               OrderWebSocketHandler webSocketHandler) {
        this.orderMapper = orderMapper;
        this.redisTemplate = redisTemplate;
        this.webSocketHandler = webSocketHandler;
    }

    /**
     * 启动时回源一次 DB 初始化内存计数（只此一次，后续靠状态迁移维护 + 定时校准兜底）
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            long db = orderMapper.countByStatus(1);
            count.set(db);
            log.info("待接单数初始化完成: count={}, instance={}", db, instanceId);
        } catch (Exception e) {
            log.warn("待接单数初始化失败，从 0 开始等待校准: instance={}, cause={}", instanceId, e.getMessage());
        }
    }

    /**
     * 状态迁移后调整计数并广播。
     *
     * @param delta 待接单数增量：下单 +1，接单/拒单/取消 -1
     */
    public void onPendingDelta(int delta) {
        long next = count.addAndGet(delta);
        if (next < 0) {
            log.warn("待接单数出现负值，归零并等待校准: next={}, instance={}", next, instanceId);
            count.set(0);
            next = 0;
        }
        publish(next);
    }

    /** 当前内存值（接口与 WebSocket 共用同一数据源，避免两处数字不一致） */
    public long get() {
        return count.get();
    }

    /** 订阅回调：落内存 + 推本机连接。发布方也走这里，因此发布处不再直接广播 */
    public void applyRemote(long value) {
        count.set(value);
        webSocketHandler.broadcastPendingCount(value);
    }

    /** 定时校准：抢到校准权的实例回源 DB，不一致才广播 */
    @Scheduled(fixedDelayString = "${drink.pending.calibrate-interval-ms:60000}")
    public void calibrate() {
        boolean locked;
        try {
            locked = Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(CALIBRATE_LOCK_KEY, instanceId, CALIBRATE_LOCK_TTL_SECONDS, TimeUnit.SECONDS));
        } catch (Exception e) {
            // Redis 不可用时退化为本机校准：各实例读的是同一个 DB，结果一致，不会互相覆盖出错值
            log.warn("校准权获取失败，退化为本机校准: cause={}", e.getMessage());
            calibrateFromDb();
            return;
        }
        if (locked) {
            calibrateFromDb();
        }
    }

    private void calibrateFromDb() {
        try {
            long memory = count.get();
            long db = orderMapper.countByStatus(1);
            if (db != memory) {
                log.warn("待接单数校准: memory={}, db={}, instance={}", memory, db, instanceId);
                count.set(db);
                publish(db);
            }
        } catch (Exception e) {
            log.warn("待接单数校准失败: {}", e.getMessage());
        }
    }

    /**
     * 发布绝对计数；Redis 不可用时降级为本地直发，保证单机管理端仍能收到推送
     */
    private void publish(long value) {
        String payload = "{\"count\":" + value + ",\"src\":\"" + instanceId + "\",\"ts\":"
                + System.currentTimeMillis() + "}";
        try {
            redisTemplate.convertAndSend(CHANNEL, payload);
        } catch (Exception e) {
            log.warn("待接单数广播降级为本地直发: count={}, cause={}", value, e.getMessage());
            webSocketHandler.broadcastPendingCount(value);
        }
    }

    /** 实例标识，仅用于日志定位消息来源 */
    String instanceId() {
        return instanceId;
    }
}
