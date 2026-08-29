package com.maaitlunghau.spring_boot_blueprint.scheduler;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.maaitlunghau.spring_boot_blueprint.common.messaging.EventPublisher;
import com.maaitlunghau.spring_boot_blueprint.common.messaging.outbox.OutboxEvent;
import com.maaitlunghau.spring_boot_blueprint.common.messaging.outbox.OutboxEventRepository;
import com.maaitlunghau.spring_boot_blueprint.common.messaging.outbox.OutboxStatus;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class OutboxPublisherScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;

    public OutboxPublisherScheduler(OutboxEventRepository outboxEventRepository, EventPublisher eventPublisher) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelay = 5000)
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        for (OutboxEvent event : pendingEvents) {
            try {
                eventPublisher.publish(event.getRoutingKey(), event.getPayload());
                event.markPublished();
            } catch (Exception e) {
                log.warn("Failed to publish outbox event {}", event.getId(), e);
                event.markFailed(e.getMessage());
            }
            
            outboxEventRepository.save(event);
        }
    }
}
