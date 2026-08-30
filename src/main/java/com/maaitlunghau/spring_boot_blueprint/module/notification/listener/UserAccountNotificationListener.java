package com.maaitlunghau.spring_boot_blueprint.module.notification.listener;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.maaitlunghau.spring_boot_blueprint.common.notification.EmailService;
import com.maaitlunghau.spring_boot_blueprint.config.RabbitMQConfig;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.EmailVerificationOtpEvent;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserBannedEvent;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserDeletedEvent;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserRestoredEvent;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserUnbannedEvent;

@Component
public class UserAccountNotificationListener {

    private final EmailService emailService;

    public UserAccountNotificationListener(EmailService emailService) {
        this.emailService = emailService;
    }

    @RabbitListener(queues = RabbitMQConfig.USER_BAN_QUEUE)
    public void onUserBanned(UserBannedEvent event) {
        String until = event.bannedUntil() == null ? "permanently" : "until " + event.bannedUntil();

        emailService.send(
            event.email(),
            "Your account has been banned",
            "Hi %s,\n\nYour account has been banned %s.\nReason: %s\n\nIf you believe this is a mistake, please contact support."
                .formatted(event.fullName(), until, event.reason())
        );
    }

    @RabbitListener(queues = RabbitMQConfig.USER_UNBAN_QUEUE)
    public void onUserUnbanned(UserUnbannedEvent event) {
        emailService.send(
            event.email(),
            "Your account has been unbanned",
            "Hi %s,\n\nYour account has been unbanned and you can now sign in again."
                .formatted(event.fullName())
        );
    }

    @RabbitListener(queues = RabbitMQConfig.USER_DELETE_QUEUE)
    public void onUserDeleted(UserDeletedEvent event) {
        emailService.send(
            event.email(),
            "Your account has been deleted",
            "Hi %s,\n\nYour account has been deleted. It will be permanently removed in 30 days unless restored by an administrator."
                .formatted(event.fullName())
        );
    }

    @RabbitListener(queues = RabbitMQConfig.USER_RESTORE_QUEUE)
    public void onUserRestored(UserRestoredEvent event) {
        emailService.send(
            event.email(),
            "Your account has been restored",
            "Hi %s,\n\nYour account has been restored and you can sign in again."
                .formatted(event.fullName())
        );
    }

    @RabbitListener(queues = RabbitMQConfig.EMAIL_VERIFICATION_QUEUE)
    public void onEmailVerificationOtpIssued(EmailVerificationOtpEvent event) {
        emailService.send(
            event.email(),
            "Verify your email address",
            "Hi %s,\n\nYour verification code is: %s\n\nThis code will expire in 10 minutes. If you did not request this, please ignore this email."
                .formatted(event.fullName(), event.otp())
        );
    }
}
