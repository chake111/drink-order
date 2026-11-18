package com.drink.server;

import com.drink.server.common.BusinessException;
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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2：同一请求 3s 内连发 2 次，order 表只应有 1 条、order_detail 只应有本次明细条数。
 * 固定连 drink_order_perf + Redis db 15，测试结束清理。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/drink_order_perf?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456",
        "spring.data.redis.database=15"
})
class OrderIdempotencyTest {

    private static final long USER_ID = 51L;
    private static final int ITEM_COUNT = 2;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private long maxOrderIdBefore;

    @BeforeEach
    void captureMaxId() {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM `order`", Long.class);
        maxOrderIdBefore = max == null ? 0L : max;
        Set<String> idemKeys = redisTemplate.keys("idem:order:*");
        if (idemKeys != null && !idemKeys.isEmpty()) {
            redisTemplate.delete(idemKeys);
        }
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM order_detail WHERE order_id > ?", maxOrderIdBefore);
        jdbcTemplate.update("DELETE FROM `order` WHERE id > ?", maxOrderIdBefore);
        Set<String> idemKeys = redisTemplate.keys("idem:order:*");
        if (idemKeys != null && !idemKeys.isEmpty()) {
            redisTemplate.delete(idemKeys);
        }
        Set<String> seqKeys = redisTemplate.keys("order:seq:*");
        if (seqKeys != null && !seqKeys.isEmpty()) {
            redisTemplate.delete(seqKeys);
        }
        Long after = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM `order`", Long.class);
        System.out.println("T2 污染检查: maxIdBefore=" + maxOrderIdBefore + ", maxIdAfterCleanup=" + after);
        assertEquals(maxOrderIdBefore, after == null ? 0L : after, "测试后未清理干净");
    }

    /**
     * 同请求连发 2 次：第 2 次必须被拦截，且 order 表仅 1 条
     */
    @Test
    void duplicateSubmitIsRejectedAndOnlyOneOrderPersisted() {
        orderService.submit(USER_ID, buildDto(false));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.submit(USER_ID, buildDto(false)));
        System.out.println("T2 重复提交结果: message=" + ex.getMessage());
        assertEquals("订单已提交，请稍候", ex.getMessage());

        Integer orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `order` WHERE id > ?", Integer.class, maxOrderIdBefore);
        Integer details = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_detail WHERE order_id > ?", Integer.class, maxOrderIdBefore);
        System.out.println("T2 落库结果: orders=" + orders + ", orderDetails=" + details
                + ", expectedDetails=" + ITEM_COUNT);

        assertEquals(1, orders, "重复请求产生了第二条 order");
        assertEquals(ITEM_COUNT, details, "重复请求产生了额外的 order_detail");
    }

    /**
     * items 顺序抖动不应击穿幂等：同一购物车换顺序仍是同一指纹
     */
    @Test
    void itemOrderDoesNotBreakIdempotency() {
        orderService.submit(USER_ID, buildDto(false));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.submit(USER_ID, buildDto(true)));
        System.out.println("T2 顺序抖动结果: message=" + ex.getMessage());

        Integer orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `order` WHERE id > ?", Integer.class, maxOrderIdBefore);
        assertEquals(1, orders, "items 顺序抖动导致重复落单");
    }

    /**
     * 幂等键确实写入了 Redis 且带 TTL
     */
    @Test
    void idemKeyIsWrittenWithTtl() {
        orderService.submit(USER_ID, buildDto(false));

        Set<String> keys = redisTemplate.keys("idem:order:*");
        assertTrue(keys != null && keys.size() == 1, "幂等键未写入或数量不为 1: " + keys);
        String key = keys.iterator().next();
        Long ttl = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("T2 幂等键: key=" + key + ", ttlSeconds=" + ttl);

        assertTrue(ttl != null && ttl > 0 && ttl <= 3, "幂等键 TTL 不在 (0,3] 区间: " + ttl);
    }

    /**
     * 业务失败后幂等键应被释放，用户可立即重试（不能被自己的失败请求阻塞 3s）
     */
    @Test
    void idemKeyIsReleasedAfterBusinessFailure() {
        OrderSubmitDTO bad = buildDto(false);
        bad.getItems().get(0).setDrinkId(999999L);

        assertThrows(BusinessException.class, () -> orderService.submit(USER_ID, bad));

        Set<String> keys = redisTemplate.keys("idem:order:*");
        System.out.println("T2 失败后幂等键残留: " + (keys == null ? 0 : keys.size()));
        assertTrue(keys == null || keys.isEmpty(), "业务失败后幂等键未释放: " + keys);
    }

    private OrderSubmitDTO buildDto(boolean reversed) {
        OrderSubmitDTO.OrderItem a = new OrderSubmitDTO.OrderItem();
        a.setDrinkId(1L);
        a.setSpec("半糖/冰");
        a.setQuantity(1);

        OrderSubmitDTO.OrderItem b = new OrderSubmitDTO.OrderItem();
        b.setDrinkId(7L);
        b.setSpec("无糖/去冰");
        b.setQuantity(2);

        List<OrderSubmitDTO.OrderItem> items = new ArrayList<>();
        items.add(b);
        items.add(a);
        if (reversed) {
            Collections.reverse(items);
        }
        OrderSubmitDTO dto = new OrderSubmitDTO();
        dto.setPickupType("自取");
        dto.setItems(items);
        return dto;
    }
}
