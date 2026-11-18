package com.drink.server;

import com.drink.server.common.BusinessException;
import com.drink.server.dto.OrderSubmitDTO;
import com.drink.server.service.OrderService;
import com.drink.server.service.PendingOrderCounter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T4 补充：状态迁移的并发幂等。
 *
 * <p>问题背景：{@code confirm/reject/cancel} 是「先查后写」，若 UPDATE 不带状态守卫，
 * 并发两次接单会两次都成功，而每次都执行 {@code changePendingCount(-1)}，
 * 导致一张单把待接单数扣两次。本用例用 CAS 之后只允许一次成功。
 *
 * <p>固定连 drink_order_perf + Redis db 15，测试结束清理。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/drink_order_perf?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456",
        "spring.data.redis.database=15",
        "drink.pending.calibrate-interval-ms=3600000"
})
class PendingCountConcurrencyTest {

    private static final long USER_ID = 51L;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PendingOrderCounter counter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private long maxOrderIdBefore;

    @BeforeEach
    void setUp() {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM `order`", Long.class);
        maxOrderIdBefore = max == null ? 0L : max;
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM order_detail WHERE order_id > ?", maxOrderIdBefore);
        jdbcTemplate.update("DELETE FROM `order` WHERE id > ?", maxOrderIdBefore);
        for (String pattern : new String[]{"order:seq:*", "idem:order:*"}) {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
        // 计数回源，避免影响后续用例
        counter.calibrate();
        Long after = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM `order`", Long.class);
        System.out.println("T4 并发污染检查: maxIdBefore=" + maxOrderIdBefore + ", maxIdAfterCleanup=" + after);
        assertEquals(maxOrderIdBefore, after == null ? 0L : after, "测试后未清理干净");
    }

    /**
     * 并发 2 次 confirm：只允许 1 次成功，待接单数只减 1
     */
    @Test
    void concurrentConfirmDeductsPendingCountOnce() throws Exception {
        long orderId = submitOneOrder("raceConfirm");
        long before = counter.get();

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        orderService.confirm(orderId);
                        success.incrementAndGet();
                    } catch (BusinessException e) {
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM `order` WHERE id = ?", Integer.class, orderId);
        long after = counter.get();
        System.out.println("T4 并发confirm: 成功=" + success.get() + ", 拒绝=" + rejected.get()
                + ", 最终status=" + status + ", countBefore=" + before + ", countAfter=" + after);

        assertEquals(1, success.get(), "并发接单应只有 1 次成功");
        assertEquals(1, rejected.get(), "另 1 次应被判为状态异常");
        assertEquals(2, status, "订单最终状态应为制作中");
        assertEquals(before - 1, after, "一张单只应把待接单数扣 1");
    }

    /**
     * 并发 2 次 reject：只允许 1 次成功，待接单数只减 1
     */
    @Test
    void concurrentRejectDeductsPendingCountOnce() throws Exception {
        long orderId = submitOneOrder("raceReject");
        long before = counter.get();

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger success = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        orderService.reject(orderId, "并发测试");
                        success.incrementAndGet();
                    } catch (BusinessException ignored) {
                        // 期望：第二次被状态异常挡下
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM `order` WHERE id = ?", Integer.class, orderId);
        long after = counter.get();
        System.out.println("T4 并发reject: 成功=" + success.get() + ", 最终status=" + status
                + ", countBefore=" + before + ", countAfter=" + after);

        assertEquals(1, success.get(), "并发拒单应只有 1 次成功");
        assertEquals(5, status, "订单最终状态应为已取消");
        assertEquals(before - 1, after, "一张单只应把待接单数扣 1");
    }

    private long submitOneOrder(String spec) {
        OrderSubmitDTO.OrderItem item = new OrderSubmitDTO.OrderItem();
        item.setDrinkId(1L);
        item.setSpec(spec);
        item.setQuantity(1);
        OrderSubmitDTO dto = new OrderSubmitDTO();
        dto.setPickupType("自取");
        dto.setItems(Collections.singletonList(item));
        orderService.submit(USER_ID, dto);
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM `order`", Long.class);
        return id == null ? 0L : id;
    }
}
