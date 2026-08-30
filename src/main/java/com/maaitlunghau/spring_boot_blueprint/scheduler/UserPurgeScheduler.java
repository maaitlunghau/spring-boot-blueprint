package com.maaitlunghau.spring_boot_blueprint.scheduler;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.maaitlunghau.spring_boot_blueprint.module.user.entity.User;
import com.maaitlunghau.spring_boot_blueprint.module.user.repository.UserRepository;
import com.maaitlunghau.spring_boot_blueprint.module.user.service.UserService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class UserPurgeScheduler {

    private final UserRepository userRepository;
    private final UserService userService;

    @Value("${app.user.soft-delete.retention-days}")
    private int retentionDays;

    public UserPurgeScheduler(UserRepository userRepository, UserService userService) {
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Scheduled(fixedDelay = 3600000)
    public void purgeExpiredSoftDeletes() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        List<User> expired = userRepository.findByDeletedAtBefore(cutoff);

        for (User user : expired) {
            try {
                userService.purgeUser(user.getId());
            } catch (Exception e) {
                log.warn("Failed to purge expired soft-deleted user {}", user.getId(), e);
            }
        }
    }
}
