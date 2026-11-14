package com.drink.server;

import com.drink.server.dto.OrderSubmitDTO;
import com.drink.server.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1：200 线程同秒下单，订单号/取餐号均不得重复。
 * 固定连 drink_order_perf，测试结束清理本次写入的行。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/drink_order_perf?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456",
        "spring.data.redis.database=15"
})
class OrderNoConcurrencyTest {

    private static final int THREADS = 200;
    private static final long USER_ID = 51L;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private long maxOrderIdBefore;

    private final List<Long> costs = java.util.Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void captureMaxId() {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM `order`", Long.class);
        maxOrderIdBefore = max == null ? 0L : max;
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM order_detail WHERE order_id > ?", maxOrderIdBefore);
        jdbcTemplate.update("DELETE FROM `order` WHERE id > ?", maxOrderIdBefore);
        Set<String> keys = redisTemplate.keys("order:seq:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        Long after = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM `order`", Long.class);
        System.out.println("T1 污染检查: maxIdBefore=" + maxOrderIdBefore + ", maxIdAfterCleanup=" + after
                + ", redisSeqKeysLeft=" + (redisTemplate.keys("order:seq:*") == null
                        ? 0 : redisTemplate.keys("order:seq:*").size()));
        assertEquals(maxOrderIdBefore, after == null ? 0L : after, "测试后未清理干净（MAX(id) 回退失败）");
    }
    @Test
    void concurrentSubmitProducesUniqueOrderNoAndPickupNo() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    long t0 = System.nanoTime();
                    orderService.submit(USER_ID, buildDto());
                    costs.add((System.nanoTime() - t0) / 1_000_000);
                    return Boolean.TRUE;
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "线程池未全部就绪");
            start.countDown();

            int succeeded = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(120, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
            assertEquals(THREADS, succeeded, "并发下单存在失败请求");
        } finally {
            pool.shutdownNow();
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT order_no, pickup_no FROM `order` WHERE id > ?", maxOrderIdBefore);
        assertEquals(THREADS, rows.size(), "落库订单数不符");

        Set<String> orderNos = new HashSet<>();
        Set<String> pickupNos = new HashSet<>();
        Set<String> secondPrefixes = new HashSet<>();
        int maxOrderNoLen = 0;
        int maxPickupNoLen = 0;
        for (Map<String, Object> row : rows) {
            String orderNo = String.valueOf(row.get("order_no"));
            String pickupNo = String.valueOf(row.get("pickup_no"));
            orderNos.add(orderNo);
            pickupNos.add(pickupNo);
            secondPrefixes.add(orderNo.substring(0, 14));
            maxOrderNoLen = Math.max(maxOrderNoLen, orderNo.length());
            maxPickupNoLen = Math.max(maxPickupNoLen, pickupNo.length());
        }

        System.out.println("T1 并发下单结果: threads=" + THREADS
                + ", rows=" + rows.size()
                + ", distinctOrderNo=" + orderNos.size()
                + ", distinctPickupNo=" + pickupNos.size()
                + ", sameSecondPrefixes=" + secondPrefixes
                + ", maxOrderNoLen=" + maxOrderNoLen
                + ", maxPickupNoLen=" + maxPickupNoLen
                + ", avgMs=" + (costs.stream().mapToLong(Long::longValue).sum() / Math.max(1, costs.size()))
                + ", maxMs=" + costs.stream().mapToLong(Long::longValue).max().orElse(0));

        assertEquals(THREADS, orderNos.size(), "订单号存在重复: " + duplicateHint(orderNos, rows, "order_no"));
        assertEquals(THREADS, pickupNos.size(), "取餐号存在重复: " + duplicateHint(pickupNos, rows, "pickup_no"));
        assertTrue(maxOrderNoLen <= 32, "订单号超长: " + maxOrderNoLen);
        assertTrue(maxPickupNoLen <= 8, "取餐号超长: " + maxPickupNoLen);
    }

    private OrderSubmitDTO buildDto() {
        OrderSubmitDTO.OrderItem item = new OrderSubmitDTO.OrderItem();
        item.setDrinkId(1L);
        item.setSpec("半糖/冰");
        item.setQuantity(1);
        OrderSubmitDTO dto = new OrderSubmitDTO();
        dto.setPickupType("自取");
        dto.setItems(Collections.singletonList(item));
        return dto;
    }

    private String duplicateHint(Set<String> distinct, List<Map<String, Object>> rows, String column) {
        if (distinct.size() == rows.size()) {
            return "none";
        }
        List<String> all = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            all.add(String.valueOf(row.get(column)));
        }
        for (String value : distinct) {
            if (Collections.frequency(all, value) > 1) {
                return value + " x" + Collections.frequency(all, value);
            }
        }
        return "unknown";
    }
}
