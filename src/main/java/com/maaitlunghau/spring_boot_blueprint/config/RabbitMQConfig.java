package com.maaitlunghau.spring_boot_blueprint.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String NOTIFICATION_EXCHANGE = "notification.exchange";
    public static final String NOTIFICATION_DLX = "notification.dlx";
    public static final String NOTIFICATION_DLQ = "notification.dlq";

    public static final String USER_BAN_QUEUE = "user.ban.notification.queue";
    public static final String USER_UNBAN_QUEUE = "user.unban.notification.queue";

    public static final String USER_BANNED_ROUTING_KEY = "user.banned";
    public static final String USER_UNBANNED_ROUTING_KEY = "user.unbanned";

    @Bean
    public TopicExchange notificationExchange() {
        return new TopicExchange(NOTIFICATION_EXCHANGE);
    }

    @Bean
    public TopicExchange notificationDeadLetterExchange() {
        return new TopicExchange(NOTIFICATION_DLX);
    }

    @Bean
    public Queue notificationDeadLetterQueue() {
        return QueueBuilder.durable(NOTIFICATION_DLQ).build();
    }

    @Bean
    public Binding notificationDeadLetterBinding() {
        return BindingBuilder.bind(notificationDeadLetterQueue()).to(notificationDeadLetterExchange()).with("#");
    }

    @Bean
    public Queue userBanNotificationQueue() {
        return QueueBuilder.durable(USER_BAN_QUEUE)
            .withArgument("x-dead-letter-exchange", NOTIFICATION_DLX)
            .build();
    }

    @Bean
    public Binding userBanBinding() {
        return BindingBuilder.bind(userBanNotificationQueue()).to(notificationExchange()).with(USER_BANNED_ROUTING_KEY);
    }

    @Bean
    public Queue userUnbanNotificationQueue() {
        return QueueBuilder.durable(USER_UNBAN_QUEUE)
            .withArgument("x-dead-letter-exchange", NOTIFICATION_DLX)
            .build();
    }

    @Bean
    public Binding userUnbanBinding() {
        return BindingBuilder.bind(userUnbanNotificationQueue()).to(notificationExchange()).with(USER_UNBANNED_ROUTING_KEY);
    }

    @Bean
    public JacksonJsonMessageConverter jacksonJsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
        ConnectionFactory connectionFactory,
        JacksonJsonMessageConverter jacksonJsonMessageConverter
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jacksonJsonMessageConverter);
        factory.setAdviceChain(
            RetryInterceptorBuilder.stateless()
                .maxRetries(3)
                .backOffOptions(1000, 2.0, 10000)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build()
        );
        return factory;
    }
}
