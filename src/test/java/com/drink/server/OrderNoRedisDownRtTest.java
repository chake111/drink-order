package com.drink.server;

import com.drink.server.common.RedisUtil;
import com.drink.server.config.OrderWebSocketHandler;
import com.drink.server.dto.OrderSubmitDTO;
import com.drink.server.mapper.DrinkMapper;
import com.drink.server.mapper.OrderDetailMapper;
import com.drink.server.mapper.OrderMapper;
import com.drink.server.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1 降级 RT：真实连接一个不可用端口（假设 6399 未监听），验证
 * ① 下单仍成功；② 降级 RT 落在超时配置量级内（不出现 2000ms 级放大）。
 * 不使用 Mock，走真实 Lettuce 连接与真实 200ms 超时。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/drink_order_perf?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456",
        "spring.data.redis.database=15"
})
class OrderNoRedisDownRtTest {

    private static final long USER_ID = 51L;
    private static final String DEAD_HOST = "127.0.0.1";
    private static final int DEAD_PORT = 6399;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private DrinkMapper drinkMapper;

    @Autowired
    private OrderWebSocketHandler webSocketHandler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long maxOrderIdBefore;
    private LettuceConnectionFactory deadFactory;

    @BeforeEach
    void captureMaxId() {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM `order`", Long.class);
        maxOrderIdBefore = max == null ? 0L : max;

        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(DEAD_HOST, DEAD_PORT);
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(200))
                .shutdownTimeout(Duration.ofMillis(100))
                .build();
        deadFactory = new LettuceConnectionFactory(standalone, clientConfig);
        deadFactory.afterPropertiesSet();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM order_detail WHERE order_id > ?", maxOrderIdBefore);
        jdbcTemplate.update("DELETE FROM `order` WHERE id > ?", maxOrderIdBefore);
        if (deadFactory != null) {
            deadFactory.destroy();
        }
    }

    @Test
    void submitSucceedsAndStaysFastWhenRedisPortIsDead() {
        StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
        deadTemplate.afterPropertiesSet();
        OrderServiceImpl service = new OrderServiceImpl(orderMapper, orderDetailMapper,
                drinkMapper, webSocketHandler, new RedisUtil(deadTemplate));

        service.submit(USER_ID, buildDto());

        int rounds = 5;
        long[] costs = new long[rounds];
        for (int i = 0; i < rounds; i++) {
            long t0 = System.nanoTime();
            Map<String, Object> result = service.submit(USER_ID, buildDto());
            costs[i] = (System.nanoTime() - t0) / 1_000_000;
            assertNotNull(result.get("orderNo"), "降级下单未返回订单号");
        }
        long max = 0;
        long sum = 0;
        for (long c : costs) {
            max = Math.max(max, c);
            sum += c;
        }
        System.out.println("T1 降级RT(不可用端口 " + DEAD_HOST + ":" + DEAD_PORT + "): rounds=" + rounds
                + ", costsMs=" + java.util.Arrays.toString(costs)
                + ", avgMs=" + (sum / rounds) + ", maxMs=" + max);

        assertTrue(max < 1500, "降级单次 RT 超过 1.5s，超时配置未生效: " + max + "ms");
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
