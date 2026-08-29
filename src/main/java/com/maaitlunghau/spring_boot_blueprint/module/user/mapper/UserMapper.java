package com.maaitlunghau.spring_boot_blueprint.module.user.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.maaitlunghau.spring_boot_blueprint.module.user.dto.request.CreateUserRequest;
import com.maaitlunghau.spring_boot_blueprint.module.user.dto.response.UserResponse;
import com.maaitlunghau.spring_boot_blueprint.module.user.entity.User;

@Mapper(componentModel = "spring")
public interface UserMapper {

    UserResponse toResponse(User user);

    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "enabled", constant = "true")
    @Mapping(target = "imageUrl", ignore = true)
    @Mapping(target = "imagePublicId", ignore = true)
    User toEntity(CreateUserRequest request);
}
