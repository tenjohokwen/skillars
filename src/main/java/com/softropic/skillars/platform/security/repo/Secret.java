package com.softropic.skillars.platform.security.repo;


import com.softropic.skillars.infrastructure.persistence.AbstractAuditingEntity;
import com.softropic.skillars.platform.security.contract.exception.SecError;
import com.softropic.skillars.platform.security.contract.exception.SecException;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.envers.Audited;
import org.jasypt.exceptions.EncryptionInitializationException;
import org.jasypt.exceptions.EncryptionOperationNotPossibleException;
import org.jasypt.util.text.BasicTextEncryptor;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

import static com.softropic.skillars.platform.security.contract.exception.SecError.BLANK_BUS_ID_OR_VERSION;
import static com.softropic.skillars.platform.security.contract.exception.SecError.BLANK_SECRET;
import static com.softropic.skillars.platform.security.repo.Secret.BUS_ID;
import static com.softropic.skillars.platform.security.repo.Secret.VERSION;


//@Getter
//@Setter
@Audited
@Entity
@Table(name = "sec" , uniqueConstraints = { @UniqueConstraint(columnNames = { VERSION, BUS_ID }) })
public class Secret extends AbstractAuditingEntity {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    public static final  String       BUS_ID        = "busId";
    public static final String VERSION = "version";

    @NotNull
    @Column(nullable = false, updatable = false)
    private String version;

    @NotNull
    @Column(nullable = false, updatable = false)
    private String busId;

    @NotNull
    @Column(nullable = false, updatable = false, columnDefinition="text", name="value")
    private String encryptedValue;

    public Secret(){}

    public Secret(String version, String busId){
        this.version = version;
        this.busId = busId;
        this.encryptedValue = generateEncryptedVal();
    }

    public byte[] getSecretBytes() {
        BasicTextEncryptor textEncryptor = new BasicTextEncryptor();
        textEncryptor.setPasswordCharArray(derivePassword());
        if(StringUtils.isBlank(encryptedValue)) {
            final String msg = "Secret value is blank. A non-blank value is needed.";
            final Map<String, Object> cxt = Map.of(BUS_ID, busId, VERSION, version);
            throw new SecException(msg, cxt, BLANK_SECRET);
        }
        try {
            final String decrypt = textEncryptor.decrypt(encryptedValue);
            return Base64.getDecoder().decode(decrypt);
        } catch (EncryptionOperationNotPossibleException | EncryptionInitializationException e) {
            throw new SecException("Could not decrypt secret", SecError.DECR_ERROR);
        }
    }

    private String generateEncryptedVal() {
        byte[] randBytes = new byte[256];
        SECURE_RANDOM.nextBytes(randBytes);
        final String secret = Base64.getEncoder().encodeToString(randBytes);
        BasicTextEncryptor textEncryptor = new BasicTextEncryptor();
        textEncryptor.setPasswordCharArray(derivePassword());
        try {
            return textEncryptor.encrypt(secret);
        } catch (EncryptionOperationNotPossibleException | EncryptionInitializationException e) {
            throw new SecException("Could not encrypt generated secret", SecError.ENCR_ERROR);
        }
    }

    private char[] derivePassword() {
        if(StringUtils.isBlank(this.busId) || StringUtils.isBlank(this.version)) {
            final String msg = "Secret is in an illegal state. Ensure busId and version are not blank";
            throw new SecException(msg, Map.of(BUS_ID, busId, VERSION, version), BLANK_BUS_ID_OR_VERSION);
        }
        final StringBuilder builder = new StringBuilder(this.busId);
        builder.append(this.version);
        return builder.reverse().toString().toCharArray();
    }

    public @NotNull String getVersion() {
        return version;
    }

    public void setVersion(@NotNull String version) {
        this.version = version;
    }

    public @NotNull String getEncryptedValue() {
        return encryptedValue;
    }

    public void setEncryptedValue(@NotNull String encryptedValue) {
        this.encryptedValue = encryptedValue;
    }

    public @NotNull String getBusId() {
        return busId;
    }

    public void setBusId(@NotNull String busId) {
        this.busId = busId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {return true;}
        if (!(o instanceof Secret secret)) {return false;}
        if (!super.equals(o)) {return false;}
        return Objects.equals(version, secret.version) &&
                Objects.equals(busId,secret.busId) &&
                Objects.equals(encryptedValue, secret.encryptedValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), version, busId, encryptedValue);
    }
}
