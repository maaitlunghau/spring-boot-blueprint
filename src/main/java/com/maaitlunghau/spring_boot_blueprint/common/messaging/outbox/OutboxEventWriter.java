package com.maaitlunghau.spring_boot_blueprint.common.messaging.outbox;

import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
public class OutboxEventWriter {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxEventWriter(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public void write(String aggregateType, UUID aggregateId, String routingKey, Object payload) {
        outboxEventRepository.save(
            OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .routingKey(routingKey)
                .payload(objectMapper.writeValueAsString(payload))
                .build()
        );
    }
}
