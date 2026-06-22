package com.softropic.skillars.platform.security.contract.event;

import java.util.UUID;

// videoId is included for audit trail: the listener must record which video triggered the suspension
// so a compliance audit can link "account suspended on date X" to "CSAM match on scan record Y".
public record AccountSuspensionRequestedEvent(String ownerId, UUID videoId) {}
