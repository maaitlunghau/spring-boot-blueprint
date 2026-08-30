package com.maaitlunghau.spring_boot_blueprint.module.user.service;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import com.maaitlunghau.spring_boot_blueprint.common.dto.PageResponse;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.BanUserRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.CreateUserRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.UpdateProfileRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.UpdateRoleRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.response.UserResponse;
import com.maaitlunghau.spring_boot_blueprint.module.user.entity.Role;

public interface UserService {

    PageResponse<UserResponse> getAllUsers(String keyword, Role role, Pageable pageable);

    PageResponse<UserResponse> getDeletedUsers(Pageable pageable);

    UserResponse getUserById(UUID id);

    UserResponse createUser(CreateUserRequest request);

    UserResponse updateProfile(UUID id, UpdateProfileRequest request);

    UserResponse updateRole(UUID id, UpdateRoleRequest request);

    UserResponse updateAvatar(UUID id, MultipartFile file);

    UserResponse banUser(UUID id, BanUserRequest request);

    UserResponse unbanUser(UUID id);

    UserResponse restoreUser(UUID id);

    void deleteUser(UUID id);

    void purgeUser(UUID id);
}
