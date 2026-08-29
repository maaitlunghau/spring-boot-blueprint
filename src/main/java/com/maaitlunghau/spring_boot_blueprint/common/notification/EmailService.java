package com.maaitlunghau.spring_boot_blueprint.common.notification;

public interface EmailService {

    void send(String to, String subject, String body);
}
