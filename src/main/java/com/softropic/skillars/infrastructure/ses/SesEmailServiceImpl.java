package com.softropic.skillars.infrastructure.ses;

import com.softropic.skillars.infrastructure.ses.exception.SesException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

@RequiredArgsConstructor
@Service
@ConditionalOnProperty(name = "app.ses.enabled", havingValue = "true", matchIfMissing = false)
public class SesEmailServiceImpl implements SesEmailService {

    private final SesV2Client sesV2Client;
    private final SesProperties props;

    @Override
    public void send(String to, String subject, String htmlBody) {
        try {
            sesV2Client.sendEmail(r -> r
                .fromEmailAddress(props.getFromAddress())
                .destination(d -> d.toAddresses(to))
                .content(c -> c.simple(m -> m
                    .subject(s -> s.data(subject))
                    .body(b -> b.html(h -> h.data(htmlBody))))));
        } catch (SesV2Exception ex) {
            throw new SesException("Failed to send email", ex);
        }
    }
}
