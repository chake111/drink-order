package com.drink.server.config;

import com.drink.server.service.PendingCountSubscriber;
import com.drink.server.service.PendingOrderCounter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 配置：订阅待接单数频道，用于多实例间的管理端推送同步。
 *
 * <p>{@link RedisMessageListenerContainer} 由 Spring 管理生命周期，随上下文自动启停。
 */
@Configuration
public class RedisPubSubConfig {

    @Bean
    public RedisMessageListenerContainer pendingCountListenerContainer(RedisConnectionFactory connectionFactory,
                                                                      PendingCountSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(PendingOrderCounter.CHANNEL));
        return container;
    }
}
