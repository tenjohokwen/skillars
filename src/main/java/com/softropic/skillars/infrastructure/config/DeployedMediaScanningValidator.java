package com.softropic.skillars.infrastructure.config;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import com.softropic.skillars.infrastructure.feature.AppFeature;
import com.softropic.skillars.infrastructure.feature.FeatureToggleService;
import com.softropic.skillars.infrastructure.videointel.VideoIntelClient;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S-18: refuses to boot a deployed ({@code prod}/{@code uat}) instance whose abuse-media scanning is unsafe:
 * <ol>
 *   <li>image scanning is off ({@code features.toggles.arachnid-enabled=false}) or on without an
 *       {@code ARACHNID_API_KEY} (every scan would fail, so no image would ever become visible, or, worse, be
 *       judged);</li>
 *   <li>{@code features.toggles.videointel-enabled=true} while the {@link VideoIntelClient} is still the stub that
 *       is not a real scanner (decision C, the real video scanner, is open).</li>
 * </ol>
 * The explicit pre-launch override {@code ALLOW_UNSCANNED_MEDIA=true} downgrades the refusal to an ERROR at boot
 * and sets the gauge {@value #GAUGE} to the number of violations, which the {@code MediaScanningUnsafe} Grafana
 * rule alerts on for as long as the instance runs that way. The override is never needed to run safely and must be
 * removed before real users upload. Values are never put in a message.
 *
 * <p>Plain {@code Environment} lookup for the override and key (same as {@link DeployedSecretsValidator}); the
 * toggle goes through {@link FeatureToggleService}, the reader the pipelines themselves use.
 */
@Component
@Profile({"prod", "uat"})
public class DeployedMediaScanningValidator {

    private static final Logger log = LoggerFactory.getLogger(DeployedMediaScanningValidator.class);

    static final String ALLOW_UNSCANNED_MEDIA = "infrastructure.media-scanning.allow-unscanned-media";
    static final String ARACHNID_API_KEY = "infrastructure.arachnid.api-key";
    static final String GAUGE = "skillars.media.scanning.unsafe";

    private final Environment env;
    private final FeatureToggleService featureToggleService;
    private final VideoIntelClient videoIntelClient;
    private final MeterRegistry meterRegistry;
    private final AtomicInteger violationCount = new AtomicInteger();

    public DeployedMediaScanningValidator(Environment env, FeatureToggleService featureToggleService,
                                          VideoIntelClient videoIntelClient, MeterRegistry meterRegistry) {
        this.env = env;
        this.featureToggleService = featureToggleService;
        this.videoIntelClient = videoIntelClient;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void validate() {
        Gauge.builder(GAUGE, violationCount, AtomicInteger::get)
            .description("Number of unsafe abuse-media scanning settings this instance runs with under ALLOW_UNSCANNED_MEDIA (0 = safe)")
            .register(meterRegistry);

        List<String> violations = violations();
        if (violations.isEmpty()) {
            return;
        }
        if (!Boolean.parseBoolean(env.getProperty(ALLOW_UNSCANNED_MEDIA))) {
            throw new AppSetupException("Unsafe abuse-media scanning in a deployed environment: "
                + String.join("; ", violations) + ". Fix the configuration, or (pre-launch only) set "
                + "ALLOW_UNSCANNED_MEDIA=true to boot anyway.");
        }
        violationCount.set(violations.size());
        log.error("[MEDIA_SCANNING_UNSAFE] ALLOW_UNSCANNED_MEDIA=true overrides: {}. Uploaded images are NOT checked "
            + "against Project Arachnid. Remove the override before real users upload.", String.join("; ", violations));
    }

    private List<String> violations() {
        List<String> violations = new ArrayList<>();
        if (!featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED)) {
            violations.add("image scanning is disabled (features.toggles.arachnid-enabled=false)");
        } else if (isBlank(env.getProperty(ARACHNID_API_KEY))) {
            violations.add("image scanning is enabled but ARACHNID_API_KEY is blank");
        }
        if (featureToggleService.isEnabled(AppFeature.VIDEOINTEL_ENABLED) && !videoIntelClient.isRealScanner()) {
            violations.add("features.toggles.videointel-enabled=true but the video scanner is a stub, not a real scanner");
        }
        return violations;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
