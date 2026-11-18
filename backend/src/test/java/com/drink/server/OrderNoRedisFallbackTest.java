package com.drink.server;

import com.drink.server.common.BusinessException;
import com.drink.server.common.RedisUtil;
import com.drink.server.config.OrderWebSocketHandler;
import com.drink.server.dto.OrderSubmitDTO;
import com.drink.server.entity.Order;
import com.drink.server.mapper.DrinkMapper;
import com.drink.server.mapper.OrderDetailMapper;
import com.drink.server.mapper.OrderMapper;
import com.drink.server.service.PendingOrderCounter;
import com.drink.server.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T1 降级与冲突重试。
 * 固定连 drink_order_perf + Redis db 15，测试结束清理本次写入的行与 seq key。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/drink_order_perf?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456",
        "spring.data.redis.database=15"
})
class OrderNoRedisFallbackTest {

    private static final long USER_ID = 51L;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private DrinkMapper drinkMapper;

    @Autowired
    private PendingOrderCounter pendingOrderCounter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long maxOrderIdBefore;

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
        System.out.println("T1 污染检查: maxIdBefore=" + maxOrderIdBefore + ", maxIdAfterCleanup=" + after);
        assertEquals(maxOrderIdBefore, after == null ? 0L : after, "测试后未清理干净（MAX(id) 回退失败）");
    }

    private OrderServiceImpl fallbackService() {
        RedisUtil brokenRedis = new RedisUtil(redisTemplate) {
            @Override
            public long incrementWithTtl(String key, long timeout, TimeUnit unit) {
                throw new RedisConnectionFailureException("simulated redis down");
            }
        };
        return new OrderServiceImpl(orderMapper, orderDetailMapper, drinkMapper, pendingOrderCounter, brokenRedis);
    }

    /**
     * 降级：Redis 不可用时仍能下单，且取餐号取订单号后 3 位、同批 200 单不重复
     */
    @Test
    void submitSucceedsWhenRedisUnavailable() throws Exception {
        OrderServiceImpl service = fallbackService();

        Map<String, Object> single = service.submit(USER_ID, buildDto("single"));
        String singleOrderNo = String.valueOf(single.get("orderNo"));
        System.out.println("T1 降级单测结果: orderNo=" + singleOrderNo + ", length=" + singleOrderNo.length());

        assertNotNull(single.get("orderNo"), "降级后未返回订单号");
        assertTrue(singleOrderNo.length() <= 32, "降级订单号超长: " + singleOrderNo.length());

        int threads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int tag = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    service.submit(USER_ID, buildDto("c" + tag));
                    return Boolean.TRUE;
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "线程池未全部就绪");
            start.countDown();
            int ok = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(120, TimeUnit.SECONDS)) {
                    ok++;
                }
            }
            assertEquals(threads, ok, "降级并发下单存在失败请求");
        } finally {
            pool.shutdownNow();
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT order_no, pickup_no FROM `order` WHERE id > ?", maxOrderIdBefore);
        assertEquals(threads + 1, rows.size(), "落库订单数不符");

        List<String> pickupNos = new ArrayList<>();
        Set<String> orderNos = new HashSet<>();
        int maxPickupLen = 0;
        for (Map<String, Object> row : rows) {
            String orderNo = String.valueOf(row.get("order_no"));
            String pickupNo = String.valueOf(row.get("pickup_no"));
            orderNos.add(orderNo);
            pickupNos.add(pickupNo);
            assertFalse("null".equals(pickupNo), "降级取餐号为 null");
            assertEquals(orderNo.substring(orderNo.length() - 3), pickupNo, "降级取餐号不等于订单号后 3 位");
            maxPickupLen = Math.max(maxPickupLen, pickupNo.length());
        }
        Set<String> distinctPickup = new HashSet<>(pickupNos);
        System.out.println("T1 降级并发结果: threads=" + threads
                + ", rows=" + rows.size()
                + ", distinctOrderNo=" + orderNos.size()
                + ", distinctPickupNo=" + distinctPickup.size()
                + ", maxPickupNoLen=" + maxPickupLen);

        assertEquals(threads + 1, distinctPickup.size(), "降级取餐号存在重复");
        assertTrue(maxPickupLen <= 8, "降级取餐号超长: " + maxPickupLen);
    }

    /**
     * 唯一索引兜底：第一次 insert 抛 DuplicateKeyException，应换号重试一次并成功落库
     */
    @Test
    void duplicateOrderNoIsRetriedOnce() {
        OrderMapper spyMapper = mock(OrderMapper.class);
        when(spyMapper.insert(any(Order.class)))
                .thenThrow(new DuplicateKeyException("simulated dup"))
                .thenAnswer(invocation -> orderMapper.insert(invocation.getArgument(0)));
        when(spyMapper.countByStatus(anyInt())).thenAnswer(invocation -> orderMapper.countByStatus(invocation.getArgument(0)));

        OrderServiceImpl service = new OrderServiceImpl(
                spyMapper, orderDetailMapper, drinkMapper, pendingOrderCounter, new RedisUtil(redisTemplate));

        Map<String, Object> result = service.submit(USER_ID, buildDto("retryOnce"));
        String orderNo = String.valueOf(result.get("orderNo"));
        System.out.println("T1 冲突重试结果: orderNo=" + orderNo);

        assertNotNull(result.get("orderNo"));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `order` WHERE order_no = ? AND id > ?", Integer.class, orderNo, maxOrderIdBefore);
        assertEquals(1, rows, "重试后订单未落库");
    }

    /**
     * 重试后仍冲突：必须抛 BusinessException，不得泄露 500
     */
    @Test
    void duplicateOrderNoTwiceThrowsBusinessException() {
        OrderMapper spyMapper = mock(OrderMapper.class);
        when(spyMapper.insert(any(Order.class))).thenThrow(new DuplicateKeyException("simulated dup"));
        when(spyMapper.countByStatus(anyInt())).thenReturn(0L);

        OrderServiceImpl service = new OrderServiceImpl(
                spyMapper, orderDetailMapper, drinkMapper, pendingOrderCounter, new RedisUtil(redisTemplate));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.submit(USER_ID, buildDto("retryTwice")));
        System.out.println("T1 二次冲突结果: message=" + ex.getMessage());
        assertEquals("下单失败，请重试", ex.getMessage());
    }

    /**
     * 正常路径 RT（真实 Redis，串行 20 次），与降级 RT 同口径对比
     */
    @Test
    void normalPathRtForComparison() {
        OrderServiceImpl service = new OrderServiceImpl(
                orderMapper, orderDetailMapper, drinkMapper, pendingOrderCounter, new RedisUtil(redisTemplate));

        service.submit(USER_ID, buildDto("rtWarmup"));
        int rounds = 20;
        long[] costs = new long[rounds];
        for (int i = 0; i < rounds; i++) {
            long t0 = System.nanoTime();
            service.submit(USER_ID, buildDto("rt" + i));
            costs[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        long max = 0;
        long sum = 0;
        for (long c : costs) {
            max = Math.max(max, c);
            sum += c;
        }
        System.out.println("T1 正常RT(真实Redis): rounds=" + rounds
                + ", costsMs=" + java.util.Arrays.toString(costs)
                + ", avgMs=" + (sum / rounds) + ", maxMs=" + max);
    }

    private OrderSubmitDTO buildDto(String tag) {
        OrderSubmitDTO.OrderItem item = new OrderSubmitDTO.OrderItem();
        item.setDrinkId(1L);
        item.setSpec("半糖/冰#" + tag);
        item.setQuantity(1);
        OrderSubmitDTO dto = new OrderSubmitDTO();
        dto.setPickupType("自取");
        dto.setItems(Collections.singletonList(item));
        return dto;
    }
}
