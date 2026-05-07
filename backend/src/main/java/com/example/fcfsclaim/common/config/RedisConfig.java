package com.example.fcfsclaim.common.config;

import com.example.fcfsclaim.domain.queue.service.QueueEndedSubscriber;
import com.example.fcfsclaim.domain.queue.service.QueueReadySubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    public static final String QUEUE_READY_CHANNEL  = "queue:ready";
    public static final String QUEUE_ENDED_CHANNEL  = "queue:ended";

    @Bean
    public StringRedisTemplate redisTemplate(RedisConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory factory,
            QueueReadySubscriber readySubscriber,
            QueueEndedSubscriber endedSubscriber) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(readySubscriber,  new ChannelTopic(QUEUE_READY_CHANNEL));
        container.addMessageListener(endedSubscriber,  new ChannelTopic(QUEUE_ENDED_CHANNEL));
        return container;
    }
}
