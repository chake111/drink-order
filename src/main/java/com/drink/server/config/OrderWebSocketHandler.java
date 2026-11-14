package com.drink.server.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单 WebSocket 处理器：维护管理端连接并向其推送待接单数。
 *
 * <p>相比最初版本的三点改动：
 * <ol>
 *   <li>连接池由裸 {@code Set<WebSocketSession>} 换成 {@code sessionId -> SessionMeta}，
 *       每个连接包一层 {@link ConcurrentWebSocketSessionDecorator}：同一连接上的并发发送被串行化，
 *       发送超时（5s）或积压（512KB）超限时抛异常而不是阻塞业务线程。</li>
 *   <li>心跳：30s 周期发协议层 Ping，客户端（浏览器）自动回 Pong；90s 无任何入站消息则判定僵死连接并清理。</li>
 *   <li>广播只读内存计数（由 {@code PendingOrderCounter} 维护），广播路径 0 条 SQL。</li>
 * </ol>
 */
@Slf4j
@Component
public class OrderWebSocketHandler extends TextWebSocketHandler {

    /** 单条消息发送耗时上限：慢客户端不能拖住广播线程 */
    private static final int SEND_TIME_LIMIT_MS = 5_000;

    /** 单连接待发送缓冲上限 */
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    /** 空闲判定：连续两个心跳周期（默认 30s×3）没有任何入站消息（Pong 也算）即清理 */
    private static final long IDLE_TIMEOUT_MS = 90_000L;

    private final Map<String, SessionMeta> sessions = new ConcurrentHashMap<>();

    /** 收到的 Pong 累计数：探针测试用它证明「服务端 Ping 确实得到客户端 Pong」 */
    private final AtomicLong pongCount = new AtomicLong();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        sessions.put(session.getId(), new SessionMeta(decorated));
        log.info("WebSocket 连接建立, id={}, 当前连接数={}", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WebSocket 连接关闭, id={}, status={}, 当前连接数={}",
                session.getId(), status, sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        touch(session.getId());
        // 兼容不走协议层 Pong 的客户端：收到应用层 "ping" 回一条 pong
        if ("ping".equalsIgnoreCase(message.getPayload())) {
            sendSafely(session.getId(), new TextMessage("{\"type\":\"pong\"}"));
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        touch(session.getId());
        pongCount.incrementAndGet();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket 传输异常，剔除连接: id={}, cause={}", session.getId(), exception.getMessage());
        closeAndRemove(session.getId(), CloseStatus.SERVER_ERROR);
    }

    /**
     * 向所有管理端连接广播待接单数量（只读内存值，无 SQL）
     */
    public void broadcastPendingCount(long count) {
        if (sessions.isEmpty()) {
            return;
        }
        TextMessage message = new TextMessage("{\"type\":\"pending\",\"count\":" + count + "}");
        for (Map.Entry<String, SessionMeta> entry : sessions.entrySet()) {
            sendSafely(entry.getKey(), message);
        }
    }

    /**
     * 心跳：发 Ping 探活，顺带清理长时间无入站消息的连接
     */
    @Scheduled(fixedDelayString = "${drink.ws.heartbeat-interval-ms:30000}")
    public void heartbeat() {
        if (sessions.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, SessionMeta> entry : sessions.entrySet()) {
            String id = entry.getKey();
            SessionMeta meta = entry.getValue();
            long idleMs = now - meta.lastActiveAt;
            if (idleMs > IDLE_TIMEOUT_MS) {
                log.info("WebSocket 空闲超时清理: id={}, idleMs={}", id, idleMs);
                closeAndRemove(id, CloseStatus.SESSION_NOT_RELIABLE);
                continue;
            }
            try {
                meta.session.sendMessage(new PingMessage());
            } catch (Exception e) {
                log.warn("WebSocket 心跳发送失败，剔除连接: id={}, cause={}", id, e.getMessage());
                closeAndRemove(id, CloseStatus.SERVER_ERROR);
            }
        }
    }

    /** 当前连接数，供测试与监控使用 */
    public int sessionCount() {
        return sessions.size();
    }

    /** 累计收到的 Pong 数，供心跳探针测试断言 */
    public long pongCount() {
        return pongCount.get();
    }

    private void touch(String sessionId) {
        SessionMeta meta = sessions.get(sessionId);
        if (meta != null) {
            meta.lastActiveAt = System.currentTimeMillis();
        }
    }

    private void sendSafely(String sessionId, TextMessage message) {
        SessionMeta meta = sessions.get(sessionId);
        if (meta == null) {
            return;
        }
        try {
            meta.session.sendMessage(message);
        } catch (Exception e) {
            log.warn("WebSocket 发送失败，剔除连接: id={}, cause={}", sessionId, e.getMessage());
            closeAndRemove(sessionId, CloseStatus.SERVER_ERROR);
        }
    }

    private void closeAndRemove(String sessionId, CloseStatus status) {
        SessionMeta meta = sessions.remove(sessionId);
        if (meta == null) {
            return;
        }
        try {
            meta.session.close(status);
        } catch (Exception e) {
            log.debug("WebSocket 关闭失败: id={}, cause={}", sessionId, e.getMessage());
        }
    }

    /** 连接元数据：装饰后的会话 + 最后一次入站消息时间 */
    private static final class SessionMeta {
        private final WebSocketSession session;
        private volatile long lastActiveAt = System.currentTimeMillis();

        private SessionMeta(WebSocketSession session) {
            this.session = session;
        }
    }
}
