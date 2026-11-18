package com.drink.server;

import com.drink.server.config.OrderWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4 心跳探针：验证「服务端发的协议层 Ping，客户端确实回了 Pong」。
 *
 * <p>为什么必须单独测：如果容器没把 Pong 上抛给 handler，`lastActiveAt` 永远不刷新，
 * 90s 空闲清理会把**所有活着的管理端连接**踢掉——这是静默故障，日志里只看得到「空闲超时清理」。
 *
 * <p>客户端用 Spring 自带 {@link StandardWebSocketClient}（JSR-356 实现）：WebSocket 规范要求端点
 * 收到 Ping 必须回 Pong，客户端在协议层自动响应，不需要业务代码。若本用例失败，说明该实现不回 Pong，
 * 则应退回应用层 JSON 心跳（服务端发 {@code {"type":"ping"}}，前端忽略未知 type）。
 *
 * <p>心跳周期压到 1s，避免用例等 30s。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/drink_order_perf?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true",
                "spring.datasource.username=root",
                "spring.datasource.password=123456",
                "spring.data.redis.database=15",
                "drink.ws.heartbeat-interval-ms=1000",
                "drink.pending.calibrate-interval-ms=3600000"
        })
class WsHeartbeatProbeTest {

    private static final long WAIT_TIMEOUT_MS = 8_000L;

    @LocalServerPort
    private int port;

    @Autowired
    private OrderWebSocketHandler handler;

    @Test
    void serverPingIsAnsweredByClientPong() throws Exception {
        long pongBefore = handler.pongCount();

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client
                .execute(new TextWebSocketHandler() {
                }, "ws://127.0.0.1:" + port + "/ws/order")
                .get(5, TimeUnit.SECONDS);

        try {
            long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline && handler.pongCount() <= pongBefore) {
                Thread.sleep(100L);
            }
            long pongAfter = handler.pongCount();
            System.out.println("T4 心跳探针: pongBefore=" + pongBefore + ", pongAfter=" + pongAfter
                    + ", sessions=" + handler.sessionCount());

            assertTrue(pongAfter > pongBefore,
                    "8s 内未收到任何 Pong：服务端 Ping 未被客户端回应，90s 空闲清理会误杀活连接");
            assertEquals(1, handler.sessionCount(), "连接建立后会话池应有 1 条连接");
        } finally {
            session.close();
        }

        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline && handler.sessionCount() > 0) {
            Thread.sleep(50L);
        }
        assertEquals(0, handler.sessionCount(), "连接关闭后会话池应清空");
    }
}
