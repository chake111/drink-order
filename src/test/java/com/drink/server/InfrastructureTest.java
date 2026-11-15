package com.drink.server;

import com.drink.server.common.BusinessException;
import com.drink.server.common.PageResult;
import com.drink.server.common.RedisUtil;
import com.drink.server.config.OrderWebSocketHandler;
import com.drink.server.dto.UserOrderPageQuery;
import com.drink.server.entity.Order;
import com.drink.server.mapper.DrinkMapper;
import com.drink.server.mapper.OrderDetailMapper;
import com.drink.server.mapper.OrderMapper;
import com.drink.server.service.PendingCountSubscriber;
import com.drink.server.service.PendingOrderCounter;
import com.drink.server.service.impl.OrderServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 不启动 Spring 的单元测试：补 WebSocket 会话管理、待接单计数、状态迁移的失败分支。
 *
 * <p>这些分支用接口测试很难触发（要么要等 90 秒空闲，要么要制造 Redis 故障），
 * 用 Mockito 直接驱动对象更可靠，也更快。
 */
class InfrastructureTest {

    // ==================== WebSocket 会话池 ====================

    /** 暴露 protected 回调，便于直接调用 */
    private static class ProbeHandler extends OrderWebSocketHandler {
        void text(WebSocketSession session, String payload) {
            handleTextMessage(session, new TextMessage(payload));
        }

        void pong(WebSocketSession session) {
            handlePongMessage(session, new PongMessage());
        }
    }

    @Test
    void appLevelPingGetsPongAndBroadcastReachesSession() throws Exception {
        ProbeHandler handler = new ProbeHandler();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s-1");
        when(session.isOpen()).thenReturn(true);

        java.util.List<String> sent = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            sent.add(((org.springframework.web.socket.WebSocketMessage<?>) inv.getArgument(0))
                    .getPayload().toString());
            return null;
        }).when(session).sendMessage(any(org.springframework.web.socket.WebSocketMessage.class));

        handler.afterConnectionEstablished(session);

        handler.text(session, "ping");
        assertTrue(sent.stream().anyMatch(p -> p.contains("pong")), "应用层 ping 未回 pong: " + sent);

        handler.broadcastPendingCount(7);
        assertTrue(sent.stream().anyMatch(p -> p.contains("\"count\":7")), "广播未送达: " + sent);

        handler.pong(session);
        assertEquals(1, handler.pongCount());

        handler.heartbeat();
        assertEquals(1, handler.sessionCount(), "活跃连接不应被心跳清理");

        handler.handleTransportError(session, new IllegalStateException("boom"));
        assertEquals(0, handler.sessionCount());
        verify(session).close(any(CloseStatus.class));
    }

    @Test
    void heartbeatEvictsIdleSessionAndToleratesEmptyPool() throws Exception {
        ProbeHandler handler = new ProbeHandler();
        handler.heartbeat();
        handler.broadcastPendingCount(0);
        assertEquals(0, handler.sessionCount());

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s-2");
        when(session.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(session);

        forceIdle(handler, "s-2");
        handler.heartbeat();

        assertEquals(0, handler.sessionCount(), "空闲连接应被清理");
        verify(session).close(CloseStatus.SESSION_NOT_RELIABLE);
    }

    @Test
    void sendFailureEvictsSession() throws Exception {
        ProbeHandler handler = new ProbeHandler();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s-3");
        when(session.isOpen()).thenReturn(true);
        doThrow(new IllegalStateException("send failed"))
                .when(session).sendMessage(any(TextMessage.class));
        handler.afterConnectionEstablished(session);

        handler.broadcastPendingCount(1);
        assertEquals(0, handler.sessionCount(), "发送失败后应剔除连接");
    }

    @SuppressWarnings("unchecked")
    private void forceIdle(OrderWebSocketHandler handler, String sessionId) throws Exception {
        Field sessionsField = OrderWebSocketHandler.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        Map<String, Object> sessions = (Map<String, Object>) sessionsField.get(handler);
        Object meta = sessions.get(sessionId);
        Field lastActive = meta.getClass().getDeclaredField("lastActiveAt");
        lastActive.setAccessible(true);
        lastActive.setLong(meta, 0L);
    }

    // ==================== 待接单计数 ====================

    @Test
    void pendingCounterClampsNegativeAndFallsBackWhenRedisDown() {
        OrderMapper mapper = mock(OrderMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        OrderWebSocketHandler handler = mock(OrderWebSocketHandler.class);
        PendingOrderCounter counter = new PendingOrderCounter(mapper, redis, handler);

        counter.onPendingDelta(-3);
        assertEquals(0, counter.get(), "计数不应为负");

        doThrow(new IllegalStateException("redis down"))
                .when(redis).convertAndSend(anyString(), any(Object.class));
        counter.onPendingDelta(2);

        assertEquals(2, counter.get());
        verify(handler).broadcastPendingCount(2L);
    }

    @Test
    void pendingCounterCalibrationBranches() {
        OrderMapper mapper = mock(OrderMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        OrderWebSocketHandler handler = mock(OrderWebSocketHandler.class);
        PendingOrderCounter counter = new PendingOrderCounter(mapper, redis, handler);

        when(ops.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        counter.calibrate();
        verify(mapper, never()).countByStatus(1);

        when(ops.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(mapper.countByStatus(1)).thenReturn(9L);
        counter.calibrate();
        assertEquals(9, counter.get(), "抢到校准权后应回源 DB");

        when(mapper.countByStatus(1)).thenReturn(12L);
        counter.calibrate();
        assertEquals(12, counter.get(), "DB 变化后应再次校准");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis, org.mockito.Mockito.atLeastOnce())
                .convertAndSend(anyString(), payload.capture());
        assertTrue(payload.getAllValues().stream().anyMatch(p -> p.contains("\"count\":12")),
                "校准后应发布新的绝对值: " + payload.getAllValues());

        when(ops.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new IllegalStateException("redis down"));
        when(mapper.countByStatus(1)).thenReturn(11L);
        counter.calibrate();
        assertEquals(11, counter.get(), "Redis 不可用时退化为本机校准");
    }

    @Test
    void pendingCounterStartupFailureKeepsZero() {
        OrderMapper mapper = mock(OrderMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        OrderWebSocketHandler handler = mock(OrderWebSocketHandler.class);
        PendingOrderCounter counter = new PendingOrderCounter(mapper, redis, handler);

        when(mapper.countByStatus(1)).thenThrow(new IllegalStateException("db down"));
        counter.run(mock(ApplicationArguments.class));
        assertEquals(0, counter.get(), "初始化失败应保持 0，等校准");

        counter.applyRemote(5);
        assertEquals(5, counter.get());
        verify(handler).broadcastPendingCount(5L);
    }

    // ==================== 订阅者 ====================

    @Test
    void subscriberIgnoresMalformedPayload() {
        PendingOrderCounter counter = mock(PendingOrderCounter.class);
        PendingCountSubscriber subscriber = new PendingCountSubscriber(new ObjectMapper(), counter);

        Message bad = mock(Message.class);
        when(bad.getBody()).thenReturn("not-json".getBytes());
        subscriber.onMessage(bad, null);
        verify(counter, never()).applyRemote(anyLong());

        Message good = mock(Message.class);
        when(good.getBody()).thenReturn("{\"count\":12,\"src\":\"x\",\"ts\":1}".getBytes());
        subscriber.onMessage(good, null);
        verify(counter).applyRemote(12L);
    }

    // ==================== Redis 工具 ====================

    @Test
    void redisUtilDelegatesBasicOperations() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(template.hasKey("k")).thenReturn(true);
        when(template.delete("k")).thenReturn(true);
        RedisUtil util = new RedisUtil(template);

        util.set("k", "v");
        verify(ops).set("k", "v");

        assertTrue(util.hasKey("k"));
        assertTrue(util.delete("k"));
    }

    // ==================== 订单状态迁移的失败分支 ====================

    @Test
    void stateTransitionsRejectConcurrentModification() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderDetailMapper detailMapper = mock(OrderDetailMapper.class);
        DrinkMapper drinkMapper = mock(DrinkMapper.class);
        PendingOrderCounter counter = mock(PendingOrderCounter.class);
        RedisUtil redisUtil = mock(RedisUtil.class);
        OrderServiceImpl service = new OrderServiceImpl(orderMapper, detailMapper, drinkMapper, counter, redisUtil);

        Order making = new Order();
        making.setId(1L);
        making.setStatus(2);
        when(orderMapper.getById(1L)).thenReturn(making);
        when(orderMapper.updateStatusIfMatch(1L, 2, 3)).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.complete(1L));

        Order ready = new Order();
        ready.setId(2L);
        ready.setStatus(3);
        when(orderMapper.getById(2L)).thenReturn(ready);
        when(orderMapper.updateStatusIfMatch(2L, 3, 4)).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.pickup(2L));

        Order pending = new Order();
        pending.setId(3L);
        pending.setStatus(1);
        when(orderMapper.getById(3L)).thenReturn(pending);
        when(orderMapper.rejectIfMatch(3L, 1, 5, "r")).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.reject(3L, "r"));

        Order mine = new Order();
        mine.setId(4L);
        mine.setUserId(51L);
        mine.setStatus(1);
        when(orderMapper.getById(4L)).thenReturn(mine);
        when(orderMapper.updateStatusIfMatch(4L, 1, 5)).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.cancel(51L, 4L));
    }

    @Test
    void paginationReturnsEmptyWhenNoOrders() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderDetailMapper detailMapper = mock(OrderDetailMapper.class);
        DrinkMapper drinkMapper = mock(DrinkMapper.class);
        PendingOrderCounter counter = mock(PendingOrderCounter.class);
        RedisUtil redisUtil = mock(RedisUtil.class);
        OrderServiceImpl service = new OrderServiceImpl(orderMapper, detailMapper, drinkMapper, counter, redisUtil);

        when(orderMapper.countByUserId(eq(99L), any())).thenReturn(0L);

        UserOrderPageQuery query = new UserOrderPageQuery();
        PageResult<Order> page = service.pageByUserId(99L, query);

        assertEquals(0L, page.getTotal());
        assertTrue(page.getRecords().isEmpty());
        verify(orderMapper, never()).listByUserIdPage(anyLong(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ==================== 分页结果对象 ====================

    @Test
    void pageResultSupportsNoArgConstruction() {
        PageResult<String> page = new PageResult<>();
        page.setTotal(3L);
        page.setRecords(java.util.List.of("a", "b", "c"));

        assertEquals(3L, page.getTotal());
        assertEquals(3, page.getRecords().size());
        assertFalse(page.getRecords().isEmpty());
    }
}
