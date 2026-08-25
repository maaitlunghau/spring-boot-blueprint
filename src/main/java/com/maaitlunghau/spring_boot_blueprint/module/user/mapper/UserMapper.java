package com.maaitlunghau.spring_boot_blueprint.module.user.mapper;

import org.mapstruct.Mapper;

import com.maaitlunghau.spring_boot_blueprint.module.user.dto.response.UserResponse;
import com.maaitlunghau.spring_boot_blueprint.module.user.entity.User;

@Mapper(componentModel = "spring")
public interface UserMapper {

    UserResponse toResponse(User user);
}
