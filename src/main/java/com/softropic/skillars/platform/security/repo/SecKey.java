package com.softropic.skillars.platform.security.repo;



import com.softropic.skillars.infrastructure.persistence.AbstractAuditingEntity;
import com.softropic.skillars.platform.security.repo.SecKeyEntityListener;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.hibernate.envers.Audited;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Hold all application secret keys
 * 1. encr_perm_key is the encrypted permuted key (encrypted using seq)
 * 2. after decryption, use seq to unpermute to get the key
 * 3. Use id and version to fetch the seckey from db (the service will fetch and decrypt encr_perm_key)
 * 4. seckey is stored in memory but with the transient field permKey in addition to encrPermKey (use @PostLoad to fill permKey after decrypting encr_perm_key)
 * 5. secretService is used to get the unpermuted key each time before use String getKey(SecKey secKey) {}
 */
@Audited
@Entity
@Table(name = "sec_key" , uniqueConstraints = { @UniqueConstraint(columnNames = { "version", "busId" }) })
@EntityListeners({SecKeyEntityListener.class})
public class SecKey extends AbstractAuditingEntity {

    @NotNull
    @Column(nullable = false, updatable = false)
    private String version;

    @NotNull
    @Column(nullable = false, updatable = false)
    private String busId;

    @NotNull
    @Column(nullable = false, updatable = false)
    private String encrPermKey;

    @Size(min = 10, max = 10)
    private transient String permKey;

    @NotNull
    @Size(min = 10, max = 10)
    @Column(nullable = false, updatable = false, length = 10)
    private String seq;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getBusId() {
        return busId;
    }

    public void setBusId(String busId) {
        this.busId = busId;
    }

    public String getEncrPermKey() {
        return encrPermKey;
    }

    public void setEncrPermKey(String encrPermKey) {
        this.encrPermKey = encrPermKey;
    }

    public String getPermKey() {
        return permKey;
    }

    public void setPermKey(String permKey) {
        this.permKey = permKey;
    }

    public String getSeq() {
        return seq;
    }

    public void setSeq(String seq) {
        this.seq = seq;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {return true;}

        if (!(o instanceof SecKey secKey)) {return false;}

        return new EqualsBuilder().appendSuper(super.equals(o))
                                  .append(version, secKey.version)
                                  .append(busId, secKey.busId)
                                  .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37).appendSuper(super.hashCode()).append(version).append(busId).toHashCode();
    }
}
