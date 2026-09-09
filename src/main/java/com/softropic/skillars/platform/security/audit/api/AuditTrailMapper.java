package com.softropic.skillars.platform.security.audit.api;



import com.softropic.skillars.platform.security.audit.repository.AuditLog;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING) //(componentModel = "spring")
public interface AuditTrailMapper {
    AuditTrail toAuditTrail(AuditLog auditLog);

    // id/createdBy/createdDate/lastModifiedBy/lastModifiedDate/status are AbstractAuditingEntity /
    // Envers plumbing set by JPA/Hibernate — never mapper-set. Per-method IGNORE keeps the default
    // WARN guard on toAuditTrail above.
    @BeanMapping(unmappedTargetPolicy = ReportingPolicy.IGNORE)
    AuditLog toAuditLog(AuditTrail auditTrail);
}
