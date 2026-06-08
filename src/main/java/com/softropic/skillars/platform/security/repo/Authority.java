package com.softropic.skillars.platform.security.repo;





import com.softropic.skillars.infrastructure.persistence.AbstractAuditingEntity;
import com.softropic.skillars.infrastructure.persistence.RequestIdAuditEntityListener;

import org.hibernate.envers.Audited;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.security.core.GrantedAuthority;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An authority (a security role) used by Spring Security.
 */
@Audited
@Entity
@Table(name = "authority")
@EntityListeners({RequestIdAuditEntityListener.class, AuditingEntityListener.class})
public class Authority extends AbstractAuditingEntity implements GrantedAuthority {

    public Authority(){}

    public Authority(String name) {
        this.name = name;
    }

    @NotNull
    @Size(min = 2, max = 50)
    @Column(length = 50, unique = true, nullable = false)
    private String name;


    public String getName() {
        return getAuthority();
    }

    public void setName(final String name) {
        this.name = name;
    }

    @Override
    public String getAuthority() {
        return name;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        final Authority authority = (Authority) obj;

        return Objects.equals(name, authority.name);
    }

    @Override
    public int hashCode() {
        return name != null ? name.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "{\"Authority\":"
                + super.toString()
                + ", \"name\":\"" + name + "\""
                + "}";
    }
}
