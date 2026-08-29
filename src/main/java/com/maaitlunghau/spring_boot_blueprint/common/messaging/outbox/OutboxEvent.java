package com.maaitlunghau.spring_boot_blueprint.common.messaging.outbox;

import java.util.UUID;

import com.maaitlunghau.spring_boot_blueprint.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;

@Entity
@Table(name = "outbox_events")
@Getter
public class OutboxEvent extends BaseEntity {

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "routing_key", nullable = false, length = 100)
    private String routingKey;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    protected OutboxEvent() {
    }

    @Builder
    public OutboxEvent(String aggregateType, UUID aggregateId, String routingKey, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.routingKey = routingKey;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.retryCount = 0;
    }

    @Override
    public String toString() {
        return "OutboxEvent{id=%s, aggregateType=%s, aggregateId=%s, routingKey=%s, status=%s}"
            .formatted(getId(), aggregateType, aggregateId, routingKey, status);
    }
}
