package com.drink.server;

import com.drink.server.common.BusinessException;
import com.drink.server.common.RedisUtil;
import com.drink.server.config.OrderWebSocketHandler;
import com.drink.server.dto.OrderSubmitDTO;
import com.drink.server.entity.Order;
import com.drink.server.mapper.DrinkMapper;
import com.drink.server.mapper.OrderDetailMapper;
import com.drink.server.mapper.OrderMapper;
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

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T1 降级：Redis 不可用时仍可下单，订单号回退为「时间戳 + 随机后缀」且长度 ≤32。
 * 固定连 drink_order_perf，测试结束清理本次写入的行。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/drink_order_perf?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456"
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
    private OrderWebSocketHandler webSocketHandler;

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
    }

    @Test
    void submitSucceedsWhenRedisUnavailable() {
        RedisUtil brokenRedis = new RedisUtil(redisTemplate) {
            @Override
            public long incrementWithTtl(String key, long timeout, TimeUnit unit) {
                throw new RedisConnectionFailureException("simulated redis down");
            }
        };
        OrderServiceImpl service = new OrderServiceImpl(
                orderMapper, orderDetailMapper, drinkMapper, webSocketHandler, brokenRedis);

        Map<String, Object> result = service.submit(USER_ID, buildDto());

        String orderNo = String.valueOf(result.get("orderNo"));
        System.out.println("T1 降级下单结果: orderNo=" + orderNo + ", length=" + orderNo.length());

        assertNotNull(result.get("orderNo"), "降级后未返回订单号");
        assertTrue(orderNo.length() <= 32, "降级订单号超长: " + orderNo.length());
        assertTrue(orderNo.matches("\\d{20}"), "降级订单号格式不符: " + orderNo);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `order` WHERE order_no = ? AND id > ?", Integer.class, orderNo, maxOrderIdBefore);
        assertEquals(1, rows, "降级订单未落库");
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
                spyMapper, orderDetailMapper, drinkMapper, webSocketHandler, new RedisUtil(redisTemplate));

        Map<String, Object> result = service.submit(USER_ID, buildDto());
        String orderNo = String.valueOf(result.get("orderNo"));
        System.out.println("T1 冲突重试结果: orderNo=" + orderNo);

        assertNotEquals(null, result.get("orderNo"));
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
                spyMapper, orderDetailMapper, drinkMapper, webSocketHandler, new RedisUtil(redisTemplate));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.submit(USER_ID, buildDto()));
        System.out.println("T1 二次冲突结果: message=" + ex.getMessage());
        assertEquals("下单失败，请重试", ex.getMessage());
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
}
