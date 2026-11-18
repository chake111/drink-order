package com.drink.server;

import com.drink.server.dto.OrderSubmitDTO;
import com.drink.server.service.OrderService;
import com.drink.server.service.PendingOrderCounter;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T4：待接单数由四条状态迁移维护，且广播路径不再回源 DB。
 *
 * <p>断言口径：内存计数增量。下单 +1、接单 -1、拒单 -1、用户取消 -1；完成/取餐不动。
 * 固定连 drink_order_perf + Redis db 15，测试结束清理数据与 Redis 残留。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/drink_order_perf?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root",
        "spring.datasource.password=123456",
        "spring.data.redis.database=15",
        // 测试期间关掉 60s 校准，避免定时任务把断言窗口里的内存值改掉
        "drink.pending.calibrate-interval-ms=3600000"
})
class PendingCountTest {

    private static final long USER_ID = 51L;

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

        List<String> ids() {
            return statementIds;
        }
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private PendingOrderCounter pendingOrderCounter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private SqlCounter sqlCounter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long maxOrderIdBefore;
    private long countBefore;

    @BeforeEach
    void setUp() throws InterruptedException {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM `order`", Long.class);
        maxOrderIdBefore = max == null ? 0L : max;
        clearRedisKeys();
        // 等上一用例的 Pub/Sub 回包落定，再取基线，避免异步消息把断言窗口打乱
        Thread.sleep(200L);
        countBefore = pendingOrderCounter.get();
        sqlCounter.reset();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM order_detail WHERE order_id > ?", maxOrderIdBefore);
        jdbcTemplate.update("DELETE FROM `order` WHERE id > ?", maxOrderIdBefore);
        clearRedisKeys();
        Long after = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM `order`", Long.class);
        System.out.println("T4 污染检查: maxIdBefore=" + maxOrderIdBefore + ", maxIdAfterCleanup=" + after);
        assertEquals(maxOrderIdBefore, after == null ? 0L : after, "测试后未清理干净");
    }

    /**
     * 下单 +1
     */
    @Test
    void submitIncrementsPendingCount() {
        orderService.submit(USER_ID, buildDto());
        long after = pendingOrderCounter.get();
        System.out.println("T4 下单: before=" + countBefore + ", after=" + after);
        assertEquals(countBefore + 1, after, "下单未把待接单数 +1");
    }

    /**
     * 接单 -1
     */
    @Test
    void confirmDecrementsPendingCount() {
        orderService.submit(USER_ID, buildDto());
        long afterSubmit = pendingOrderCounter.get();
        orderService.confirm(newestOrderId());
        long afterConfirm = pendingOrderCounter.get();
        System.out.println("T4 接单: afterSubmit=" + afterSubmit + ", afterConfirm=" + afterConfirm);
        assertEquals(afterSubmit - 1, afterConfirm, "接单未把待接单数 -1");
    }

    /**
     * 拒单 -1
     */
    @Test
    void rejectDecrementsPendingCount() {
        orderService.submit(USER_ID, buildDto());
        long afterSubmit = pendingOrderCounter.get();
        orderService.reject(newestOrderId(), "原料不足");
        long afterReject = pendingOrderCounter.get();
        System.out.println("T4 拒单: afterSubmit=" + afterSubmit + ", afterReject=" + afterReject);
        assertEquals(afterSubmit - 1, afterReject, "拒单未把待接单数 -1");
    }

    /**
     * 用户取消 -1
     */
    @Test
    void cancelDecrementsPendingCount() {
        orderService.submit(USER_ID, buildDto());
        long afterSubmit = pendingOrderCounter.get();
        orderService.cancel(USER_ID, newestOrderId());
        long afterCancel = pendingOrderCounter.get();
        System.out.println("T4 取消: afterSubmit=" + afterSubmit + ", afterCancel=" + afterCancel);
        assertEquals(afterSubmit - 1, afterCancel, "取消未把待接单数 -1");
    }

    /**
     * 四条迁移的累计期望值：C → C+3 → C+2 → C+1 → C，且 complete/pickup 不参与计数
     */
    @Test
    void sequenceOfTransitionsMatchesExpectedCount() {
        long o1 = submitAndGetId(1);
        long o2 = submitAndGetId(2);
        long o3 = submitAndGetId(3);
        assertEquals(countBefore + 3, pendingOrderCounter.get(), "3 次下单后应为 C+3");

        orderService.confirm(o1);
        assertEquals(countBefore + 2, pendingOrderCounter.get(), "接单 1 单后应为 C+2");

        orderService.reject(o2, "原料不足");
        assertEquals(countBefore + 1, pendingOrderCounter.get(), "拒单 1 单后应为 C+1");

        orderService.cancel(USER_ID, o3);
        assertEquals(countBefore, pendingOrderCounter.get(), "取消 1 单后应回到 C");

        // complete（2→3）与 pickup（3→4）不进出待接单集合，计数不变
        orderService.complete(o1);
        orderService.pickup(o1);
        assertEquals(countBefore, pendingOrderCounter.get(), "完成/取餐不应改变待接单数");

        System.out.println("T4 累计序列: C=" + countBefore + ", 终值=" + pendingOrderCounter.get());
    }

    /**
     * 事务回滚：计数增减注册在 afterCommit，回滚时不得动计数（也不得落单）
     */
    @Test
    void rollbackDoesNotChangePendingCount() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () -> template.execute(status -> {
            orderService.submit(USER_ID, buildDto(1));
            throw new IllegalStateException("模拟事务回滚");
        }));

        Integer persisted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `order` WHERE id > ?", Integer.class, maxOrderIdBefore);
        System.out.println("T4 回滚: 落库单数=" + persisted + ", 计数=" + pendingOrderCounter.get());

        assertEquals(0, persisted, "回滚后不应有订单落库");
        assertEquals(countBefore, pendingOrderCounter.get(), "事务回滚不应改变待接单数");
    }

    /**
     * 广播路径 0 条 SQL：状态迁移只允许 getById + update，不允许再出现 countByStatus
     */
    @Test
    void broadcastPathIssuesNoPendingCountQuery() {
        orderService.submit(USER_ID, buildDto());
        sqlCounter.reset();

        orderService.confirm(newestOrderId());

        List<String> statements = new ArrayList<>();
        for (String id : sqlCounter.ids()) {
            statements.add(id.substring(id.lastIndexOf('.') + 1));
        }
        System.out.println("T4 接单路径 SQL: " + statements);
        assertFalse(statements.contains("countByStatus"),
                "广播路径仍在回源 DB 统计待接单数: " + statements);
    }

    private long newestOrderId() {
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM `order` WHERE id > ?", Long.class, maxOrderIdBefore);
        return id == null ? 0L : id;
    }

    private void clearRedisKeys() {
        for (String pattern : new String[]{"order:seq:*", "idem:order:*"}) {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
    }

    private OrderSubmitDTO buildDto() {
        return buildDto(1);
    }

    private OrderSubmitDTO buildDto(int quantity) {
        OrderSubmitDTO.OrderItem item = new OrderSubmitDTO.OrderItem();
        item.setDrinkId(1L);
        item.setSpec("半糖/冰");
        item.setQuantity(quantity);

        List<OrderSubmitDTO.OrderItem> items = new ArrayList<>();
        items.add(item);

        OrderSubmitDTO dto = new OrderSubmitDTO();
        dto.setPickupType("自取");
        dto.setItems(items);
        return dto;
    }

    /**
     * 下单并取回新单 id。quantity 参与指纹，保证连续多次下单不会被 3s 防重拦掉
     */
    private long submitAndGetId(int quantity) {
        orderService.submit(USER_ID, buildDto(quantity));
        return newestOrderId();
    }
}
