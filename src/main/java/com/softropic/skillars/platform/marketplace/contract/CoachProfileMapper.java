package com.softropic.skillars.platform.marketplace.contract;

import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindow;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CoachProfileMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "displayName", source = "req.displayName")
    @Mapping(target = "bio", ignore = true)
    @Mapping(target = "city", source = "req.city")
    @Mapping(target = "district", source = "req.district")
    @Mapping(target = "languages", source = "req.languages")
    @Mapping(target = "canonicalTimezone", source = "req.canonicalTimezone")
    @Mapping(target = "photoUrl", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    CoachProfile toEntity(ProfileBuilderStep1Request req, Long userId);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "coachId", source = "coachId")
    @Mapping(target = "dayOfWeek", source = "req.dayOfWeek")
    @Mapping(target = "startTime", source = "req.startTime")
    @Mapping(target = "endTime", source = "req.endTime")
    @Mapping(target = "canonicalTimezone", source = "req.canonicalTimezone")
    CoachAvailabilityWindow toEntity(ProfileBuilderStep4Request.AvailabilityWindowRequest req, java.util.UUID coachId);
}
