package com.softropic.skillars.platform.config.service;

import com.softropic.skillars.platform.config.contract.ConfigValueResponse;
import com.softropic.skillars.platform.config.repo.PlatformConfig;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ConfigMapper {

    ConfigValueResponse toResponse(PlatformConfig entity);
}
