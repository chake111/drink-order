package com.drink.server;

import com.drink.server.dto.OrderPageQuery;
import com.drink.server.dto.OrderSubmitDTO;
import com.drink.server.service.OrderService;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3：SQL 次数断言。用 MyBatis Interceptor 统计真实执行的语句数，
 * 比日志 grep 更可复现（不依赖日志级别与时间窗口）。
 * 固定连 drink_order_perf + Redis db 15，测试结束清理。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/drink_order_perf?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456",
        "spring.data.redis.database=15"
})
class OrderPageQuerySqlCountTest {

    private static final long USER_ID = 51L;

    @TestConfiguration
    static class CountingConfig {
        @Bean
        SqlCounter sqlCounter() {
            return new SqlCounter();
        }
    }

    /**
     * 统计 query 次数并记录 statement id，便于断言「1 条 count + 1 条主表 + 1 条 IN」
     */
    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
    })
    public static class SqlCounter implements Interceptor {
        private final AtomicInteger queryCount = new AtomicInteger();
        private final List<String> statementIds = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
            queryCount.incrementAndGet();
            statementIds.add(ms.getId());
            return invocation.proceed();
        }

        void reset() {
            queryCount.set(0);
            statementIds.clear();
        }

        int count() {
            return queryCount.get();
        }

        List<String> ids() {
            return statementIds;
        }
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private SqlCounter counter;

    private long maxOrderIdBefore;

    @BeforeEach
    void setUp() {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM `order`", Long.class);
        maxOrderIdBefore = max == null ? 0L : max;
        counter.reset();
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
        Long after = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM `order`", Long.class);
        System.out.println("T3 污染检查: maxIdBefore=" + maxOrderIdBefore + ", maxIdAfterCleanup=" + after);
        assertEquals(maxOrderIdBefore, after == null ? 0L : after, "测试后未清理干净");
    }

    /**
     * pageSize=10：必须恰好 3 条 SQL（count + 主表 + 明细 IN），且明细只查一次
     */
    @Test
    void pageQueryIssuesExactlyThreeStatements() {
        seedOrders(3);
        counter.reset();

        OrderPageQuery query = new OrderPageQuery();
        query.setPage(1);
        query.setPageSize(10);
        orderService.pageQuery(query);

        List<String> ids = counter.ids().stream()
                .map(id -> id.substring(id.lastIndexOf('.') + 1))
                .collect(Collectors.toList());
        System.out.println("T3 order/page SQL 次数=" + counter.count() + ", statements=" + ids);

        assertEquals(3, counter.count(), "pageQuery SQL 条数不符: " + ids);
        assertEquals(1, ids.stream().filter("count"::equals).count(), "count 语句不是 1 条");
        assertEquals(1, ids.stream().filter("pageQuery"::equals).count(), "主表分页语句不是 1 条");
        assertEquals(1, ids.stream().filter("listByOrderIds"::equals).count(), "明细批量语句不是 1 条");
    }

    /**
     * 明细装配合并到 1 条 IN 查询后，返回的明细内容必须与逐单查询等价
     */
    @Test
    void batchedDetailsMatchPerOrderDetails() {
        seedOrders(3);
        counter.reset();

        OrderPageQuery query = new OrderPageQuery();
        query.setPage(1);
        query.setPageSize(10);
        var page = orderService.pageQuery(query);

        int batchedDetailRows = page.getRecords().stream()
                .mapToInt(o -> o.getDetails().size()).sum();

        // 期望值按「本次分页实际返回的订单 ID」逐单计数，避免历史数据干扰
        int expected = 0;
        for (var order : page.getRecords()) {
            Integer one = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM order_detail WHERE order_id = ?", Integer.class, order.getId());
            expected += one == null ? 0 : one;
        }

        System.out.println("T3 批量装配明细行数=" + batchedDetailRows + ", 逐单期望=" + expected
                + ", orders=" + page.getRecords().size());

        assertTrue(page.getRecords().size() >= 3, "分页结果少于造的数据");
        assertEquals(expected, batchedDetailRows, "批量装配的明细行数与逐单查询不一致");
    }

    /**
     * 用户端列表：N 单也只用 2 条 SQL（列表 + 明细 IN），且不得再出现逐单查询
     */
    @Test
    void userOrderListIssuesExactlyTwoStatements() {
        seedOrders(3);
        counter.reset();

        var orders = orderService.listByUserId(USER_ID, null);

        List<String> ids = counter.ids().stream()
                .map(id -> id.substring(id.lastIndexOf('.') + 1))
                .collect(Collectors.toList());
        System.out.println("T3 user/order/list SQL 次数=" + counter.count() + ", statements=" + ids
                + ", orders=" + orders.size());

        assertEquals(2, counter.count(), "用户端列表 SQL 条数不符: " + ids);
        assertEquals(0, ids.stream().filter("listByOrderId"::equals).count(),
                "仍在逐单查询明细（listByOrderId 不应出现）");
        assertEquals(1, ids.stream().filter("listByOrderIds"::equals).count(),
                "缺少批量明细查询 listByOrderIds");
    }

    /**
     * 历史恰好 500 单时：分段 IN 恰好 1 批（每批 ≤500），SQL 总数仍为 2，未退化为 N+1。
     * 使用无历史订单的独立 userId，保证「500 单」是精确值。
     */
    @Test
    void userOrderListWith500OrdersStillTwoStatements() {
        long isolatedUser = 990001L;
        seedOrdersDirectly(isolatedUser, 500);
        counter.reset();

        long t0 = System.nanoTime();
        var orders = orderService.listByUserId(isolatedUser, null);
        long costMs = (System.nanoTime() - t0) / 1_000_000;

        List<String> ids = counter.ids().stream()
                .map(id -> id.substring(id.lastIndexOf('.') + 1))
                .collect(Collectors.toList());
        System.out.println("T3 恰好500单: SQL 次数=" + counter.count() + ", statements=" + ids
                + ", orders=" + orders.size() + ", costMs=" + costMs);

        assertEquals(500, orders.size(), "返回订单数应为 500");
        assertEquals(2, counter.count(), "500 单场景 SQL 条数不符: " + ids);
        assertEquals(0, ids.stream().filter("listByOrderId"::equals).count(), "出现逐单查询");
        assertEquals(1, ids.stream().filter("listByOrderIds"::equals).count(), "明细批次数不为 1");
    }

    /**
     * 501 单时跨到第 2 批：SQL 变为 3 条，证明分段边界行为符合 ceil(N/500)
     */
    @Test
    void userOrderListWith501OrdersUsesTwoBatches() {
        long isolatedUser = 990002L;
        seedOrdersDirectly(isolatedUser, 501);
        counter.reset();

        var orders = orderService.listByUserId(isolatedUser, null);

        List<String> ids = counter.ids().stream()
                .map(id -> id.substring(id.lastIndexOf('.') + 1))
                .collect(Collectors.toList());
        System.out.println("T3 501单: SQL 次数=" + counter.count() + ", statements=" + ids
                + ", orders=" + orders.size());

        assertEquals(501, orders.size(), "返回订单数应为 501");
        assertEquals(3, counter.count(), "501 单场景应为 2 个明细批次（共 3 条 SQL）: " + ids);
        assertEquals(2, ids.stream().filter("listByOrderIds"::equals).count(), "明细批次数不为 2");
    }

    /**
     * 直接批量插入订单与明细，避免通过 submit 造 500 单过慢
     */
    private void seedOrdersDirectly(long userId, int count) {
        List<Object[]> orderRows = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            orderRows.add(new Object[]{
                    userId, "T3BULK" + System.nanoTime() + i, new java.math.BigDecimal("12.00"), 4, "自取", null
            });
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO `order` (user_id, order_no, amount, status, pickup_type, pickup_no) VALUES (?,?,?,?,?,?)",
                orderRows);

        List<Long> newIds = jdbcTemplate.queryForList(
                "SELECT id FROM `order` WHERE id > ? AND user_id = ? ORDER BY id", Long.class, maxOrderIdBefore, userId);
        List<Object[]> detailRows = new java.util.ArrayList<>(count);
        for (Long id : newIds) {
            detailRows.add(new Object[]{id, 1L, "美式", "/images/ame.png", "", 1, new java.math.BigDecimal("12.00")});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO order_detail (order_id, drink_id, drink_name, image, spec, quantity, price) VALUES (?,?,?,?,?,?,?)",
                detailRows);
    }

    private void seedOrdersDirectly(int count) {
        seedOrdersDirectly(USER_ID, count);
    }

    private void seedOrders(int count) {
        for (int i = 0; i < count; i++) {
            OrderSubmitDTO.OrderItem item = new OrderSubmitDTO.OrderItem();
            item.setDrinkId(1L);
            item.setSpec("T3seed#" + i);
            item.setQuantity(1);
            OrderSubmitDTO dto = new OrderSubmitDTO();
            dto.setPickupType("自取");
            dto.setItems(java.util.Collections.singletonList(item));
            orderService.submit(USER_ID, dto);
        }
    }
}
