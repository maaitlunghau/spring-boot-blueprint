package com.maaitlunghau.spring_boot_blueprint.module.user.service.impl;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.maaitlunghau.spring_boot_blueprint.common.dto.PageResponse;
import com.maaitlunghau.spring_boot_blueprint.common.messaging.outbox.OutboxEventWriter;
import com.maaitlunghau.spring_boot_blueprint.common.storage.ImageTransform;
import com.maaitlunghau.spring_boot_blueprint.config.RabbitMQConfig;
import com.maaitlunghau.spring_boot_blueprint.common.storage.StorageResult;
import com.maaitlunghau.spring_boot_blueprint.common.storage.StorageService;
import com.maaitlunghau.spring_boot_blueprint.exception.BadRequestException;
import com.maaitlunghau.spring_boot_blueprint.exception.DuplicateResourceException;
import com.maaitlunghau.spring_boot_blueprint.exception.EmailPendingPurgeException;
import com.maaitlunghau.spring_boot_blueprint.exception.InvalidOtpException;
import com.maaitlunghau.spring_boot_blueprint.exception.ResendCooldownException;
import com.maaitlunghau.spring_boot_blueprint.exception.ResourceNotFoundException;
import com.maaitlunghau.spring_boot_blueprint.exception.UserAlreadyVerifiedException;
import com.maaitlunghau.spring_boot_blueprint.exception.UserAlreadyBannedException;
import com.maaitlunghau.spring_boot_blueprint.exception.UserNotBannedException;
import com.maaitlunghau.spring_boot_blueprint.exception.UserNotDeletedException;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.BanUserRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.CreateUserRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.UpdateProfileRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.UpdateRoleRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.UserFilterRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.response.UserResponse;
import com.maaitlunghau.spring_boot_blueprint.module.user.entity.EmailVerificationToken;
import com.maaitlunghau.spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.spring_boot_blueprint.module.user.entity.User;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.EmailVerificationOtpEvent;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserBannedEvent;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserDeletedEvent;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserRestoredEvent;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserUnbannedEvent;
import com.maaitlunghau.spring_boot_blueprint.module.user.mapper.UserMapper;
import com.maaitlunghau.spring_boot_blueprint.module.user.repository.EmailVerificationTokenRepository;
import com.maaitlunghau.spring_boot_blueprint.module.user.repository.UserRepository;
import com.maaitlunghau.spring_boot_blueprint.module.user.repository.spec.UserSpecifications;
import com.maaitlunghau.spring_boot_blueprint.module.user.service.UserService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private static final String AVATAR_FOLDER = "avatars";
    private static final Set<String> ALLOWED_AVATAR_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_AVATAR_SIZE = 5 * 1024 * 1024;
    private static final ImageTransform AVATAR_TRANSFORM = new ImageTransform(512, 512, true);

    private static final String USER_AGGREGATE_TYPE = "User";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final StorageService storageService;
    private final OutboxEventWriter outboxEventWriter;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Value("${app.avatar.default-url}")
    private String defaultAvatarUrl;

    @Value("${app.email-verification.otp-expiration-minutes}")
    private int otpExpirationMinutes;

    @Value("${app.email-verification.resend-cooldown-seconds}")
    private long resendCooldownSeconds;

    public UserServiceImpl(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        UserMapper userMapper,
        StorageService storageService,
        OutboxEventWriter outboxEventWriter,
        EmailVerificationTokenRepository emailVerificationTokenRepository
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.storageService = storageService;
        this.outboxEventWriter = outboxEventWriter;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
    }

    @Override
    public PageResponse<UserResponse> getAllUsers(UserFilterRequest filter, Pageable pageable) {
        Page<User> page = userRepository.findAll(
            buildFilterSpec(filter).and(UserSpecifications.notDeleted()), pageable
        );

        return PageResponse.from(page.map(userMapper::toResponse));
    }

    @Override
    public PageResponse<UserResponse> getDeletedUsers(UserFilterRequest filter, Pageable pageable) {
        Page<User> page = userRepository.findAll(
            buildFilterSpec(filter).and(UserSpecifications.onlyDeleted()), pageable
        );

        return PageResponse.from(page.map(userMapper::toResponse));
    }

    private Specification<User> buildFilterSpec(UserFilterRequest filter) {
        return UserSpecifications.keywordIn(filter.keyword())
            .and(UserSpecifications.hasRole(filter.role()))
            .and(UserSpecifications.isBanned(filter.banned()))
            .and(UserSpecifications.isEmailVerified(filter.emailVerified()))
            .and(UserSpecifications.instantBetween("createdAt", filter.createdFrom(), filter.createdTo()))
            .and(UserSpecifications.instantBetween("bannedAt", filter.bannedAtFrom(), filter.bannedAtTo()))
            .and(UserSpecifications.instantBetween("bannedUntil", filter.bannedUntilFrom(), filter.bannedUntilTo()));
    }

    @Override
    public UserResponse getUserById(UUID id) {
        return userRepository.findByIdAndDeletedAtIsNull(id)
            .map(userMapper::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));
    }

    @Override
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        userRepository.findByEmail(request.email()).ifPresent(existing -> {
            if (existing.getDeletedAt() != null) {
                throw new EmailPendingPurgeException();
            }
            throw new DuplicateResourceException("User", request.email());
        });

        User user = userMapper.toEntity(request);
        user.changePassword(passwordEncoder.encode(request.password()));
        user.updateAvatar(defaultAvatarUrl, null);

        User saved = userRepository.save(user);
        issueVerificationOtp(saved);

        return userMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public UserResponse updateProfile(UUID id, UpdateProfileRequest request) {
        User user = userRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));

        user.updateProfile(request.fullName());

        return userMapper.toResponse(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserResponse updateRole(UUID id, UpdateRoleRequest request) {
        User user = userRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));

        user.changeRole(Role.valueOf(request.role()));

        return userMapper.toResponse(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserResponse banUser(UUID id, BanUserRequest request) {
        User user = userRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));

        if (user.getRole() == Role.ADMIN) {
            throw new BadRequestException("Cannot ban a user with ADMIN role");
        }
        // Self-ban is not checked here: there is no authenticated-caller identity
        // available yet. See docs/AUTH_MODULE_TODO.md.
        if (!user.isEnabled()) {
            throw new UserAlreadyBannedException(id.toString());
        }

        user.ban(request.reason(), request.bannedUntil());
        UserResponse response = userMapper.toResponse(userRepository.save(user));

        outboxEventWriter.write(
            USER_AGGREGATE_TYPE,
            id,
            RabbitMQConfig.USER_BANNED_ROUTING_KEY,
            new UserBannedEvent(id, user.getEmail(), user.getFullName(), request.reason(), request.bannedUntil())
        );

        return response;
    }

    @Override
    @Transactional
    public UserResponse unbanUser(UUID id) {
        User user = userRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));

        if (user.isEnabled()) {
            throw new UserNotBannedException(id.toString());
        }

        user.unban();
        UserResponse response = userMapper.toResponse(userRepository.save(user));

        outboxEventWriter.write(
            USER_AGGREGATE_TYPE,
            id,
            RabbitMQConfig.USER_UNBANNED_ROUTING_KEY,
            new UserUnbannedEvent(id, user.getEmail(), user.getFullName())
        );

        return response;
    }

    @Override
    @Transactional
    public UserResponse restoreUser(UUID id) {
        User user = userRepository.findByIdAndDeletedAtIsNotNull(id)
            .orElseThrow(() -> new UserNotDeletedException(id.toString()));

        user.restore();
        UserResponse response = userMapper.toResponse(userRepository.save(user));

        outboxEventWriter.write(
            USER_AGGREGATE_TYPE,
            id,
            RabbitMQConfig.USER_RESTORED_ROUTING_KEY,
            new UserRestoredEvent(id, user.getEmail(), user.getFullName())
        );

        return response;
    }

    @Override
    @Transactional(noRollbackFor = InvalidOtpException.class)
    public UserResponse verifyEmail(UUID id, String otp) {
        User user = userRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));

        if (user.isEmailVerified()) {
            throw new UserAlreadyVerifiedException(id.toString());
        }

        EmailVerificationToken token = emailVerificationTokenRepository.findTopByUserIdOrderByCreatedAtDesc(id)
            .filter(t -> !t.isUsed() && !t.isExpired() && !t.isAttemptsExceeded())
            .orElseThrow(InvalidOtpException::new);

        if (!passwordEncoder.matches(otp, token.getOtpHash())) {
            token.incrementAttempt();
            emailVerificationTokenRepository.save(token);
            throw new InvalidOtpException();
        }

        token.markUsed();
        emailVerificationTokenRepository.save(token);

        user.verifyEmail();
        return userMapper.toResponse(userRepository.save(user));
    }

    @Override
    @Transactional
    public void resendVerificationOtp(UUID id) {
        User user = userRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));

        if (user.isEmailVerified()) {
            throw new UserAlreadyVerifiedException(id.toString());
        }

        emailVerificationTokenRepository
            .findTopByUserIdOrderByCreatedAtDesc(id)
            .ifPresent(latest -> {
                Instant cooldownEnds = latest.getCreatedAt().plusSeconds(resendCooldownSeconds);

                if (Instant.now().isBefore(cooldownEnds)) {
                    throw new ResendCooldownException(Duration.between(Instant.now(), cooldownEnds).getSeconds());
                }
        });

        issueVerificationOtp(user);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UserResponse updateAvatar(UUID id, MultipartFile file) {
        validateAvatarFile(file);

        if (!userRepository.existsByIdAndDeletedAtIsNull(id)) {
            throw new ResourceNotFoundException("User", id.toString());
        }

        StorageResult result = storageService.upload(file, AVATAR_FOLDER, AVATAR_TRANSFORM);

        User user = userRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));

        String oldPublicId = user.getImagePublicId();
        user.updateAvatar(result.url(), result.publicId());
        UserResponse response = userMapper.toResponse(userRepository.save(user));

        if (oldPublicId != null) {
            try {
                storageService.delete(oldPublicId);
            } catch (Exception e) {
                log.warn("Failed to delete old avatar '{}' for user {}", oldPublicId, id, e);
            }
        }

        return response;
    }

    @Override
    @Transactional
    public void deleteUser(UUID id) {
        User user = userRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));

        if (user.getRole() == Role.ADMIN) {
            throw new BadRequestException("Cannot delete a user with ADMIN role");
        }

        user.softDelete();
        userRepository.save(user);

        outboxEventWriter.write(
            USER_AGGREGATE_TYPE,
            id,
            RabbitMQConfig.USER_DELETED_ROUTING_KEY,
            new UserDeletedEvent(id, user.getEmail(), user.getFullName())
        );
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void purgeUser(UUID id) {
        User user = userRepository.findByIdAndDeletedAtIsNotNull(id)
            .orElseThrow(() -> new UserNotDeletedException(id.toString()));

        userRepository.deleteById(id);

        if (user.getImagePublicId() != null) {
            try {
                storageService.delete(user.getImagePublicId());
            } catch (Exception e) {
                log.warn("Failed to delete avatar '{}' for purged user {}", user.getImagePublicId(), id, e);
            }
        }
    }

    private String generateOtp() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private void issueVerificationOtp(User user) {
        String otp = generateOtp();

        EmailVerificationToken token = EmailVerificationToken.builder()
            .userId(user.getId())
            .otpHash(passwordEncoder.encode(otp))
            .expiresAt(Instant.now().plus(otpExpirationMinutes, ChronoUnit.MINUTES))
            .build();
        emailVerificationTokenRepository.save(token);

        outboxEventWriter.write(
            USER_AGGREGATE_TYPE,
            user.getId(),
            RabbitMQConfig.EMAIL_VERIFICATION_ROUTING_KEY,
            new EmailVerificationOtpEvent(user.getId(), user.getEmail(), user.getFullName(), otp)
        );
    }

    private void validateAvatarFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File is required");
        }

        if (!ALLOWED_AVATAR_TYPES.contains(file.getContentType())) {
            throw new BadRequestException("Only JPG, PNG, WEBP images are allowed");
        }
        
        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new BadRequestException("File size must not exceed 5MB");
        }
    }
}
