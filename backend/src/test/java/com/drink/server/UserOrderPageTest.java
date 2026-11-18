package com.drink.server;

import com.drink.server.common.PageResult;
import com.drink.server.dto.OrderSubmitDTO;
import com.drink.server.dto.UserOrderPageQuery;
import com.drink.server.entity.Order;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5：用户端订单分页接口。断言分页边界、pageSize 上限、状态过滤、明细装配、
 * 用户隔离与 SQL 条数。
 *
 * <p>固定连 drink_order_perf + Redis db 15。用零历史用户 990051/990052 造数：
 * 库里 user 51 已有历史订单，直接用它需要把断言写成「历史数 + N」。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/drink_order_perf?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456",
        "spring.data.redis.database=15",
        // 关掉 60s 校准，避免后台任务改动待接单数干扰造单路径
        "drink.pending.calibrate-interval-ms=3600000"
})
class UserOrderPageTest {

    private static final long USER_ID = 990051L;
    private static final long OTHER_USER_ID = 990052L;
    private static final long EMPTY_USER_ID = 990053L;
    private static final int SEED_COUNT = 25;

    @TestConfiguration
    static class CountingConfig {
        @Bean
        SqlCounter sqlCounter() {
            return new SqlCounter();
        }
    }

    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
    })
    public static class SqlCounter implements Interceptor {
        private final AtomicInteger queryCount = new AtomicInteger();
        private final List<String> statementIds = new CopyOnWriteArrayList<>();

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
        clearRedisKeys();
        counter.reset();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM order_detail WHERE order_id > ?", maxOrderIdBefore);
        jdbcTemplate.update("DELETE FROM `order` WHERE id > ?", maxOrderIdBefore);
        clearRedisKeys();
        Long after = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM `order`", Long.class);
        System.out.println("T5 污染检查: maxIdBefore=" + maxOrderIdBefore + ", maxIdAfterCleanup=" + after);
        assertEquals(maxOrderIdBefore, after == null ? 0L : after, "测试后未清理干净");
    }

    /**
     * 第 1 页：total 为全量 25，records 收口到 pageSize
     */
    @Test
    void firstPageReturnsTenRecords() {
        seedOrders(USER_ID, SEED_COUNT);

        PageResult<Order> page = orderService.pageByUserId(USER_ID, query(1, 10, null));

        System.out.println("T5 第1页: total=" + page.getTotal() + ", records=" + page.getRecords().size());
        assertEquals(SEED_COUNT, page.getTotal(), "total 不是该用户订单总数");
        assertEquals(10, page.getRecords().size(), "第 1 页条数不等于 pageSize");
    }

    /**
     * 末页：25 单按 10 条分页，第 3 页剩 5 条
     */
    @Test
    void lastPageReturnsRemainingFive() {
        seedOrders(USER_ID, SEED_COUNT);

        PageResult<Order> page = orderService.pageByUserId(USER_ID, query(3, 10, null));

        System.out.println("T5 末页: total=" + page.getTotal() + ", records=" + page.getRecords().size());
        assertEquals(SEED_COUNT, page.getTotal(), "total 不是该用户订单总数");
        assertEquals(5, page.getRecords().size(), "末页条数不等于余量");
    }

    /**
     * 越界页：records 为空，total 仍为全量
     */
    @Test
    void pageBeyondLastIsEmptyButKeepsTotal() {
        seedOrders(USER_ID, SEED_COUNT);

        PageResult<Order> page = orderService.pageByUserId(USER_ID, query(4, 10, null));

        System.out.println("T5 越界页: total=" + page.getTotal() + ", records=" + page.getRecords().size());
        assertTrue(page.getRecords().isEmpty(), "越界页不应返回数据");
        assertEquals(SEED_COUNT, page.getTotal(), "越界页的 total 被改写了");
    }

    /**
     * pageSize 上限：传 500 按 50 收口
     */
    @Test
    void pageSizeCappedAtFifty() {
        seedOrders(USER_ID, SEED_COUNT);

        UserOrderPageQuery query = query(1, 500, null);
        PageResult<Order> page = orderService.pageByUserId(USER_ID, query);

        System.out.println("T5 pageSize 上限: pageSize=" + query.getPageSize()
                + ", records=" + page.getRecords().size());
        assertEquals(50, query.getPageSize(), "pageSize 未收口到上限");
        assertEquals(SEED_COUNT, page.getRecords().size(), "25 单应全量返回");
    }

    /**
     * page 下限：传 0 按 1 处理，不得算出负 offset
     */
    @Test
    void pageBelowOneClampedToFirstPage() {
        seedOrders(USER_ID, SEED_COUNT);

        UserOrderPageQuery query = query(0, 10, null);
        PageResult<Order> page = orderService.pageByUserId(USER_ID, query);

        System.out.println("T5 page 下限: page=" + query.getPage() + ", records=" + page.getRecords().size());
        assertEquals(1, query.getPage(), "page 未收口到下限");
        assertEquals(10, page.getRecords().size(), "page=0 未按第 1 页返回");
    }

    /**
     * 状态过滤：25 单里 confirm 5 单，status=2 只应命中这 5 单
     */
    @Test
    void statusFilterMatchesConfirmedOrders() {
        seedOrders(USER_ID, SEED_COUNT);
        List<Long> ids = seededOrderIds(USER_ID, SEED_COUNT);
        for (int i = 0; i < 5; i++) {
            orderService.confirm(ids.get(i));
        }

        PageResult<Order> page = orderService.pageByUserId(USER_ID, query(1, 10, 2));

        System.out.println("T5 状态过滤: total=" + page.getTotal()
                + ", records=" + page.getRecords().size());
        assertEquals(5, page.getTotal(), "status=2 的 total 不对");
        assertEquals(5, page.getRecords().size(), "status=2 的 records 条数不对");
        assertTrue(page.getRecords().stream().allMatch(o -> o.getStatus() == 2),
                "过滤结果里混入了非制作中订单");
    }

    /**
     * 明细只装配当前页，且条数等于下单时的 item 数（每单 2 个 item）
     */
    @Test
    void detailsFilledForCurrentPageOnly() {
        seedOrders(USER_ID, SEED_COUNT);

        PageResult<Order> page = orderService.pageByUserId(USER_ID, query(1, 10, null));

        assertTrue(page.getRecords().size() > 0, "第 1 页没有数据");
        for (Order order : page.getRecords()) {
            assertEquals(2, order.getDetails().size(),
                    "订单 " + order.getId() + " 的明细条数与下单 item 数不符");
        }
        System.out.println("T5 明细装配: orders=" + page.getRecords().size()
                + ", detailsPerOrder=" + page.getRecords().get(0).getDetails().size());
    }

    /**
     * 用户隔离：另一个用户下单不影响原用户的 total
     */
    @Test
    void otherUsersOrdersDoNotLeakIntoTotal() {
        seedOrders(USER_ID, SEED_COUNT);
        seedOrders(OTHER_USER_ID, 3);

        PageResult<Order> mine = orderService.pageByUserId(USER_ID, query(1, 10, null));
        PageResult<Order> others = orderService.pageByUserId(OTHER_USER_ID, query(1, 10, null));

        System.out.println("T5 用户隔离: mine.total=" + mine.getTotal()
                + ", other.total=" + others.getTotal());
        assertEquals(SEED_COUNT, mine.getTotal(), "原用户 total 被其他用户的订单污染");
        assertEquals(3, others.getTotal(), "另一用户的 total 不对");
        assertTrue(mine.getRecords().stream().allMatch(o -> o.getUserId().equals(USER_ID)),
                "返回了其他用户的订单");
    }

    /**
     * 一次分页请求恰好 3 条 SQL：countByUserId + listByUserIdPage + listByOrderIds
     */
    @Test
    void pageByUserIssuesExactlyThreeStatements() {
        seedOrders(USER_ID, SEED_COUNT);
        counter.reset();

        orderService.pageByUserId(USER_ID, query(1, 10, null));

        List<String> ids = counter.ids().stream()
                .map(id -> id.substring(id.lastIndexOf('.') + 1))
                .collect(Collectors.toList());
        System.out.println("T5 分页 SQL 次数=" + counter.count() + ", statements=" + ids);

        assertEquals(3, counter.count(), "分页 SQL 条数不符: " + ids);
        assertEquals(1, ids.stream().filter("countByUserId"::equals).count(), "count 语句不是 1 条");
        assertEquals(1, ids.stream().filter("listByUserIdPage"::equals).count(), "分页主表语句不是 1 条");
        assertEquals(1, ids.stream().filter("listByOrderIds"::equals).count(), "明细批量语句不是 1 条");
    }

    /**
     * 空结果：total=0 时不查列表，只发 1 条 count
     */
    @Test
    void emptyResultIssuesOnlyCountStatement() {
        counter.reset();

        PageResult<Order> page = orderService.pageByUserId(EMPTY_USER_ID, query(1, 10, null));

        List<String> ids = counter.ids().stream()
                .map(id -> id.substring(id.lastIndexOf('.') + 1))
                .collect(Collectors.toList());
        System.out.println("T5 空结果: total=" + page.getTotal() + ", statements=" + ids);

        assertEquals(0L, page.getTotal(), "空用户的 total 应为 0");
        assertTrue(page.getRecords().isEmpty(), "空用户的 records 应为空");
        assertEquals(1, counter.count(), "空结果不应再查列表: " + ids);
        assertEquals(1, ids.stream().filter("countByUserId"::equals).count(), "缺少 count 语句");
    }

    private UserOrderPageQuery query(Integer page, Integer pageSize, Integer status) {
        UserOrderPageQuery query = new UserOrderPageQuery();
        query.setPage(page);
        query.setPageSize(pageSize);
        query.setStatus(status);
        return query;
    }

    /**
     * 通过 submit 造单：每单 2 个 item，spec 带序号保证指纹不同，避开 3s 防重
     */
    private void seedOrders(long userId, int count) {
        for (int i = 0; i < count; i++) {
            List<OrderSubmitDTO.OrderItem> items = new ArrayList<>();
            items.add(item(1L, "T5seed#" + i, 1));
            items.add(item(2L, "T5seed#" + i + "b", 1));

            OrderSubmitDTO dto = new OrderSubmitDTO();
            dto.setPickupType("自取");
            dto.setItems(items);
            orderService.submit(userId, dto);
        }
    }

    private OrderSubmitDTO.OrderItem item(Long drinkId, String spec, int quantity) {
        OrderSubmitDTO.OrderItem item = new OrderSubmitDTO.OrderItem();
        item.setDrinkId(drinkId);
        item.setSpec(spec);
        item.setQuantity(quantity);
        return item;
    }

    private List<Long> seededOrderIds(long userId, int count) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM `order` WHERE id > ? AND user_id = ? ORDER BY id LIMIT ?",
                Long.class, maxOrderIdBefore, userId, count);
    }

    private void clearRedisKeys() {
        for (String pattern : new String[]{"order:seq:*", "idem:order:*"}) {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
    }
}
