package com.softropic.skillars.platform.security.service;



import com.softropic.skillars.infrastructure.persistence.EntityStatus;
import com.softropic.skillars.platform.security.contract.exception.SecError;
import com.softropic.skillars.platform.security.contract.exception.SecException;
import com.softropic.skillars.platform.security.repo.Secret;
import com.softropic.skillars.platform.security.repo.SecretRepository;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Transactional
public class SecretService {

    private static final int SEC_KEY_MAP_MAX_SIZE = 10;
    public static final String ACTION = "Action";
    public static final String BUS_ID = "busId";
    public static final String VERSION = "version";
    public static final String KEY_NOT_FOUND_MSG = "Cannot find requested secret";

    private final SecretRepository secretRepository;

    private final Map<String, Secret> secretMap = new ConcurrentHashMap<>(SEC_KEY_MAP_MAX_SIZE);

    public SecretService(SecretRepository secretRepository) {this.secretRepository = secretRepository;}

    public Secret createInactiveSecret(String version, String busId) {
        return createSecret(version, busId, false);
    }

    public Secret createActiveSecret(String version, String busId) {
        return createSecret(version, busId, true);
    }

    private Secret createSecret(String version, String busId, boolean active) {
        final Map<String, Object> ctx = Map.of(ACTION, "secret creation", VERSION, version, BUS_ID, busId);
        validateBusId(busId, ctx);
        validateVersion(version, ctx);

        final Secret secret = new Secret(version, busId);
        secret.setStatus(active ? EntityStatus.ACTIVE : EntityStatus.INACTIVE);

        return secretRepository.save(secret);
    }


    public byte[] fetchSecretAsBytes(String version, String busId) {
        return fetchSecret(version, busId).getSecretBytes();
    }

    public Secret fetchSecret(String version, String busId) {
        final Map<String, Object> ctx = Map.of(ACTION, "secret fetch", VERSION, version, BUS_ID, busId);
        validateVersion(version, ctx);
        validateBusId(busId, ctx);
        final Optional<Secret> secretOpt = secretRepository.findOneByVersionAndBusId(version, busId);
        return secretOpt.orElseThrow(() -> new SecException(KEY_NOT_FOUND_MSG,
                                                            ctx,
                                                            SecError.KEY_NOT_FOUND));
    }

    public byte[] fetchLatestActiveSecretAsBytes(String busId) {
        final Map<String, Object> ctx = new HashMap<>(Map.of(ACTION, "fetch LATEST secret",
                                                             BUS_ID, busId));
        validateBusId(busId, ctx);
        final Secret secret = secretMap.get(busId);
        if(secret != null) {
            return secret.getSecretBytes();
        }
        final Optional<Secret> secretOpt = secretRepository.findTopByBusIdAndStatusOrderByCreatedDateDesc(busId, EntityStatus.ACTIVE);
        final Secret sec = secretOpt.orElseThrow(() -> new SecException(KEY_NOT_FOUND_MSG,
                                                                           ctx,
                                                                           SecError.KEY_NOT_FOUND));
        final byte[] fetchedBytes = sec.getSecretBytes();
        if(secretMap.size() < SEC_KEY_MAP_MAX_SIZE) {
            secretMap.put(busId, sec);
        }
        return fetchedBytes;
    }


    private void validateBusId(String busId, Map<String, Object> context) {
        if(StringUtils.isBlank(busId)) {
            throw new SecException("Business id cannot be blank", context, SecError.BLANK_BUS_ID);
        }
    }

    private void validateVersion(String version, Map<String, Object> context) {
        if(StringUtils.isBlank(version)) {
            throw new SecException("Version cannot be blank", context, SecError.BLANK_VERSION);
        }
    }
}
