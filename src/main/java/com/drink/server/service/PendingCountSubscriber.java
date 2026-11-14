package com.drink.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 待接单数频道订阅者：把绝对计数落到本机内存并推给本机管理端连接。
 *
 * <p>载荷形如 {@code {"count":N,"src":"<instanceId>","ts":<millis>}}，绝对计数幂等，
 * 重复投递或乱序都不会把计数带偏（旧消息可能短暂回退，下一次状态迁移或校准即修正）。
 */
@Slf4j
@Service
public class PendingCountSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final PendingOrderCounter counter;

    public PendingCountSubscriber(ObjectMapper objectMapper, PendingOrderCounter counter) {
        this.objectMapper = objectMapper;
        this.counter = counter;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            PendingCountMessage msg = objectMapper.readValue(body, PendingCountMessage.class);
            counter.applyRemote(msg.getCount());
        } catch (Exception e) {
            log.warn("待接单数订阅消息解析失败: body={}, cause={}", body, e.getMessage());
        }
    }

    @Data
    public static class PendingCountMessage {
        private long count;
        private String src;
        private long ts;
    }
}
