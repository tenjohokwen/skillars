package com.softropic.skillars.platform.booking.contract;

import com.softropic.skillars.platform.booking.repo.SessionPackPurchased;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface SessionPackMapper {
    SessionPackPurchasedResponse toResponse(SessionPackPurchased pack, String coachDisplayName);
}
