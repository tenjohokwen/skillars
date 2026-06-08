package com.softropic.skillars.platform.security.contract.event;


import com.softropic.skillars.platform.notification.contract.EmailTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SendMailEvent(List<Long> userIds,
                            EmailTemplate emailTemplate,
                            Instant deadline,
                            Map<String, Object> data,
                            String sendId) {
}
