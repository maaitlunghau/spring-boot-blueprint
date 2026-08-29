package com.maaitlunghau.spring_boot_blueprint.common.messaging;

public interface EventPublisher {

    void publish(String routingKey, String jsonPayload);
}
