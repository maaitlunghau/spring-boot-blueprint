package com.maaitlunghau.spring_boot_blueprint.module.user.service.impl;

import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import com.maaitlunghau.spring_boot_blueprint.exception.ResourceNotFoundException;
import com.maaitlunghau.spring_boot_blueprint.exception.UserAlreadyBannedException;
import com.maaitlunghau.spring_boot_blueprint.exception.UserNotBannedException;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.BanUserRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.CreateUserRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.UpdateProfileRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.UpdateRoleRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.response.UserResponse;
import com.maaitlunghau.spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.spring_boot_blueprint.module.user.entity.User;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserBannedEvent;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserDeletedEvent;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserUnbannedEvent;
import com.maaitlunghau.spring_boot_blueprint.module.user.mapper.UserMapper;
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

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final StorageService storageService;
    private final OutboxEventWriter outboxEventWriter;

    @Value("${app.avatar.default-url}")
    private String defaultAvatarUrl;

    public UserServiceImpl(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        UserMapper userMapper,
        StorageService storageService,
        OutboxEventWriter outboxEventWriter
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.storageService = storageService;
        this.outboxEventWriter = outboxEventWriter;
    }

    @Override
    public PageResponse<UserResponse> getAllUsers(String keyword, Role role, Pageable pageable) {
        Page<User> page = userRepository.findAll(
            UserSpecifications.keywordIn(keyword).and(UserSpecifications.hasRole(role)).and(UserSpecifications.notDeleted()), pageable
        );
        
        return PageResponse.from(page.map(userMapper::toResponse));
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
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("User", request.email());
        }

        User user = userMapper.toEntity(request);
        user.changePassword(passwordEncoder.encode(request.password()));
        user.updateAvatar(defaultAvatarUrl, null);

        return userMapper.toResponse(userRepository.save(user));
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
