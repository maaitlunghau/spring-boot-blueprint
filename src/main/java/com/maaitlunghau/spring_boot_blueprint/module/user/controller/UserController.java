package com.maaitlunghau.spring_boot_blueprint.module.user.controller;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.maaitlunghau.spring_boot_blueprint.common.dto.ApiResponse;
import com.maaitlunghau.spring_boot_blueprint.common.dto.PageResponse;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.CreateUserRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.UpdateProfileRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.UpdateRoleRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.response.UserResponse;
import com.maaitlunghau.spring_boot_blueprint.module.user.entity.Role;
import com.maaitlunghau.spring_boot_blueprint.module.user.service.UserService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping()
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> search(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) Role role,
        Pageable pageable
    ) {
        return ResponseEntity.ok(
            ApiResponse.ok(userService.getAllUsers(keyword, role, pageable))
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable UUID id) {
        return ResponseEntity.ok(
            ApiResponse.ok(userService.getUserById(id))
        );
    }

    @PostMapping()
    public ResponseEntity<ApiResponse<UserResponse>> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.of(
                HttpStatus.CREATED.value(), 
                "User created successfully", 
                userService.createUser(request)
            )
        );
    }

    @PatchMapping("/{id}/profile")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
        @PathVariable UUID id,
        @Valid @RequestBody UpdateProfileRequest request
    ) {
        return ResponseEntity.ok(
            ApiResponse.of(
                HttpStatus.OK.value(), 
                "Updated profile successfully", 
                userService.updateProfile(id, request)
            )
        );
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<ApiResponse<UserResponse>> updateRole(
        @PathVariable UUID id,
        @Valid @RequestBody UpdateRoleRequest request
    ) {
        return ResponseEntity.ok(
            ApiResponse.of(
                HttpStatus.OK.value(), 
                "Updated role successfully", 
                userService.updateRole(id, request)
            )
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable UUID id) {
        userService.deleteUser(id);
        return ResponseEntity.ok(
            ApiResponse.message(
                HttpStatus.OK.value(), 
                "User deleted successfully"
            )
        );
    }
}
