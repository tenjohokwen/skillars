package com.softropic.skillars.platform.security.service;



import com.softropic.skillars.infrastructure.persistence.EntityStatus;
import com.softropic.skillars.platform.security.contract.PermutedSecretKey;
import com.softropic.skillars.platform.security.contract.exception.SecError;
import com.softropic.skillars.platform.security.contract.exception.SecException;
import com.softropic.skillars.platform.security.repo.SecKey;
import com.softropic.skillars.platform.security.repo.SecKeyRepository;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * For now this service allows secrets of only 10 charaters
 * Given "etejsKywtT" as key and SecretService generates the following sequence "8240579163";
 * SecretService will permute the key then encrypt it and store in the db
 * When fetched from db it is decrypted but may or may not be reverse-permutated.
 * It is advisable to store only permutated keys in memory.
 */
@Service
@Transactional
public class SecKeyService {

    private static final int SEC_KEY_MAP_MAX_SIZE = 10;
    public static final String ACTION = "Action";
    public static final String BUS_ID = "busId";
    public static final String KEY_NOT_FOUND_MSG = "Cannot find requested key";

    private final SecKeyRepository secKeyRepository;

    private final Map<String, PermutedSecretKey> secretKeyMap = new ConcurrentHashMap<>(3);

    public SecKeyService(SecKeyRepository secKeyRepository) {this.secKeyRepository = secKeyRepository;}

    public SecKey createInactiveSecKey(String version, String busId, String key) {
        return createSecKey(version, busId, key, false);
    }

    public SecKey createActiveSecKey(String version, String busId, String key) {
        return createSecKey(version, busId, key, true);
    }

    private SecKey createSecKey(String version, String busId, String key, boolean active) {
        final Map<String, Object> ctx = Map.of(ACTION, "key creation");
        validateKey(key, ctx);
        validateBusId(busId, ctx);
        validateVersion(version, ctx);

        final PermutedSecretKey permutedSecretKey = PermutedSecretKey.instanceFromPlainKey(key);
        String encrPermKey = permutedSecretKey.getEncryptedPermKey();


        final SecKey secKey = new SecKey();
        secKey.setEncrPermKey(encrPermKey);
        secKey.setBusId(busId);
        secKey.setSeq(permutedSecretKey.getSequence());
        secKey.setVersion(version);
        secKey.setStatus(active ? EntityStatus.ACTIVE : EntityStatus.INACTIVE);

        return secKeyRepository.save(secKey);
    }

    public String fetchKey(String version, String busId) {
        final SecKey secKey = fetchSecKey(version, busId);
        return extractKey(secKey.getPermKey(), secKey.getSeq());
    }

    public String fetchPermKey(String version, String busId) {
        final SecKey secKey = fetchSecKey(version, busId);
        return secKey.getPermKey();
    }

    private SecKey fetchSecKey(String version, String busId) {
        final Map<String, Object> ctx = new HashMap<>(Map.of(ACTION, "key fetch"));
        validateVersion(version, ctx);
        validateBusId(busId, ctx);
        final Optional<SecKey> optSecKey = secKeyRepository.findOneByVersionAndBusId(version, busId);
        ctx.put("version", version);
        ctx.put(BUS_ID, busId);
        return optSecKey.orElseThrow(() -> new SecException(KEY_NOT_FOUND_MSG,
                                                            ctx,
                                                            SecError.KEY_NOT_FOUND));
    }

    public String fetchLatestActivePermKey(String busId) {
        PermutedSecretKey permutedSecretKey = secretKeyMap.get(busId);
        if(permutedSecretKey != null) {
            return permutedSecretKey.getPermKey();
        }
        final Map<String, Object> ctx = new HashMap<>(Map.of(ACTION, "fetch LATEST Permkey",
                                                             BUS_ID, busId));
        final Optional<SecKey> optSecKey = fetchLatestActiveSecKey(busId, ctx);
        final SecKey secKey = optSecKey.orElseThrow(() -> new SecException(KEY_NOT_FOUND_MSG,
                                                                           ctx,
                                                                           SecError.KEY_NOT_FOUND));
        if(secretKeyMap.keySet().size() <= SEC_KEY_MAP_MAX_SIZE) {
            secretKeyMap.put(busId, PermutedSecretKey.instanceFromPermutedKey(secKey.getPermKey(), secKey.getSeq()));
        }
        return secKey.getPermKey();
    }

    public PermutedSecretKey fetchLatestActivePermSecKey(String busId) {
        PermutedSecretKey permutedSecretKey = secretKeyMap.get(busId);
        if(permutedSecretKey == null) {
            final Map<String, Object> ctx = new HashMap<>(Map.of(ACTION, "fetch LATEST Permkey",
                                                                 BUS_ID, busId));
            final Optional<SecKey> optSecKey = fetchLatestActiveSecKey(busId, ctx);
            final SecKey secKey = optSecKey.orElseThrow(() -> new SecException(KEY_NOT_FOUND_MSG,
                                                                               ctx,
                                                                               SecError.KEY_NOT_FOUND));
            permutedSecretKey = PermutedSecretKey.instanceFromPermutedKey(secKey.getPermKey(), secKey.getSeq());
            if(permutedSecretKey != null && secretKeyMap.keySet().size() <= SEC_KEY_MAP_MAX_SIZE){
                secretKeyMap.put(busId, permutedSecretKey);
            }
        }
        return permutedSecretKey;
    }

    Optional<SecKey> fetchLatestActiveSecKey(String busId, Map<String, Object> ctx) {
        validateBusId(busId, ctx);
        return secKeyRepository.findTopByBusIdAndStatusOrderByCreatedDateDesc(busId, EntityStatus.ACTIVE);
    }

    public String keyFromPermKey(String permKey, String sequence) {
        return PermutedSecretKey.instanceFromPermutedKey(permKey, sequence).getPlainKey();
    }

    String extractKey(SecKey secKey) {
        final Map<String, Object> ctx = new HashMap<>(Map.of(ACTION, "extracting and un-permutating key"));
        final String version = secKey.getVersion();
        final String busId = secKey.getBusId();
        ctx.put("version", version);
        ctx.put(BUS_ID, busId);
        validateVersion(version, ctx);
        validateBusId(busId, ctx);
        return extractKey(secKey.getPermKey(), secKey.getSeq());
    }

    private String extractKey(String permKey, String sequence) {
        final PermutedSecretKey permutedSecretKey = PermutedSecretKey.instanceFromPermutedKey(permKey, sequence);
        return permutedSecretKey.getPlainKey();
    }

    private void validateKey(String key, Map<String, Object> context) {
        if(StringUtils.isBlank(key)) {
            throw new SecException("secret key cannot be blank", context, SecError.BLANK_KEY);
        }
        if(key.length() != SEC_KEY_MAP_MAX_SIZE) {
            throw new SecException("Key length should be 10", context, SecError.ILLEGAL_KEY_LENGTH);
        }
    }

    private void validateBusId(String busId, Map<String, Object> context) {
        if(StringUtils.isBlank(busId)) {
            throw new SecException("Business id cannot be blank", context, SecError.BLANK_BUS_ID);
        }
    }

    private void validateVersion(String version, Map<String, Object> context) {
        if(StringUtils.isBlank(version)) {
            throw new SecException("Version cannot be blank", context, SecError.BLANK_BUS_ID);
        }
    }
}
