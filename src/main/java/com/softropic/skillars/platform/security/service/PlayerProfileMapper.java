package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.contract.PlayerProfileResponse;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PlayerProfileMapper {

    @Mapping(target = "ageTierLabel", expression = "java(profile.getAgeTier().displayLabel())")
    PlayerProfileResponse toResponse(PlayerProfile profile);
}
