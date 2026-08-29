package com.maaitlunghau.spring_boot_blueprint.common.messaging.rabbit;

import java.nio.charset.StandardCharsets;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.maaitlunghau.spring_boot_blueprint.common.messaging.EventPublisher;
import com.maaitlunghau.spring_boot_blueprint.config.RabbitMQConfig;

@Component
public class RabbitEventPublisher implements EventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public RabbitEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(String routingKey, String jsonPayload) {
        Message message = MessageBuilder
            .withBody(jsonPayload.getBytes(StandardCharsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .build();

        rabbitTemplate.send(RabbitMQConfig.NOTIFICATION_EXCHANGE, routingKey, message);
    }
}
