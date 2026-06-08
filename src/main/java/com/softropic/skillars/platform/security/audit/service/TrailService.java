package com.softropic.skillars.platform.security.audit.service;



import com.softropic.skillars.platform.security.audit.api.AuditTrail;
import com.softropic.skillars.platform.security.audit.api.AuditTrailMapper;
import com.softropic.skillars.platform.security.audit.repository.AuditLog;
import com.softropic.skillars.platform.security.audit.repository.AuditLogRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW) //this txn should not affect others
public class TrailService {

    private final AuditLogRepository     auditLogRepo;
    private final AuditTrailMapper auditTrailMapper;

    public TrailService(AuditLogRepository auditLogRepo, AuditTrailMapper auditTrailMapper) {
        this.auditLogRepo = auditLogRepo;
        this.auditTrailMapper = auditTrailMapper;
    }

    public void recordTrail(AuditTrail auditTrail) {
        final AuditLog auditLog = auditTrailMapper.toAuditLog(auditTrail);
        auditLogRepo.save(auditLog);
    }

}
