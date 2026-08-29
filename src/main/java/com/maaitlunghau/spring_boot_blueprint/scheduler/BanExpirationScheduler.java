package com.maaitlunghau.spring_boot_blueprint.scheduler;

import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.maaitlunghau.spring_boot_blueprint.module.user.entity.User;
import com.maaitlunghau.spring_boot_blueprint.module.user.repository.UserRepository;
import com.maaitlunghau.spring_boot_blueprint.module.user.service.UserService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class BanExpirationScheduler {

    private final UserRepository userRepository;
    private final UserService userService;

    public BanExpirationScheduler(UserRepository userRepository, UserService userService) {
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Scheduled(fixedDelay = 300000)
    public void unbanExpiredUsers() {
        List<User> expiredBans = userRepository.findByEnabledFalseAndBannedUntilBefore(Instant.now());

        for (User user : expiredBans) {
            try {
                userService.unbanUser(user.getId());
            } catch (Exception e) {
                log.warn("Failed to auto-unban expired ban for user {}", user.getId(), e);
            }
        }
    }
}
