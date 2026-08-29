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
import com.maaitlunghau.spring_boot_blueprint.common.storage.StorageResult;
import com.maaitlunghau.spring_boot_blueprint.common.storage.StorageService;
import com.maaitlunghau.spring_boot_blueprint.exception.BadRequestException;
import com.maaitlunghau.spring_boot_blueprint.exception.DuplicateResourceException;
import com.maaitlunghau.spring_boot_blueprint.exception.ResourceNotFoundException;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.CreateUserRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.UpdateProfileRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.UpdateRoleRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.response.UserResponse;
import com.maaitlunghau.spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.spring_boot_blueprint.module.user.entity.User;
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

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final StorageService storageService;

    @Value("${app.avatar.default-url}")
    private String defaultAvatarUrl;

    public UserServiceImpl(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        UserMapper userMapper,
        StorageService storageService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.storageService = storageService;
    }

    @Override
    public PageResponse<UserResponse> getAllUsers(String keyword, Role role, Pageable pageable) {
        Page<User> page = userRepository.findAll(
            UserSpecifications.keywordIn(keyword).and(UserSpecifications.hasRole(role)), pageable
        );
        
        return PageResponse.from(page.map(userMapper::toResponse));
    }

    @Override
    public UserResponse getUserById(UUID id) {
        return userRepository.findById(id)
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
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));

        user.updateProfile(request.fullName());

        return userMapper.toResponse(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserResponse updateRole(UUID id, UpdateRoleRequest request) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));

        user.changeRole(Role.valueOf(request.role()));

        return userMapper.toResponse(userRepository.save(user));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UserResponse updateAvatar(UUID id, MultipartFile file) {
        validateAvatarFile(file);

        if (!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("User", id.toString());
        }

        StorageResult result = storageService.upload(file, AVATAR_FOLDER);

        User user = userRepository.findById(id)
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
        if (!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("User", id.toString());
        }

        userRepository.deleteById(id);
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
