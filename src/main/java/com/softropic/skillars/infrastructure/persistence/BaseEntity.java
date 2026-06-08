package com.softropic.skillars.infrastructure.persistence;


import java.io.Serializable;
import java.util.Objects;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;


/**
 * All objects inheriting BaseEntity do not need to implement `equals()` and `hashCode()` methods because BaseEntity provides a generic impl
 * The equals is based on the id field (same reference will also be equal)
 * You can override the `equals()` and `hashCode()` methods if you desire something else
 */
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@MappedSuperclass
public abstract class BaseEntity implements Serializable {

    @Id @Tsid
    @Column(name = "id", updatable = false, nullable = false) //TODO ensure it is unique
    protected Long id;

    public Long getId() {
        return id;
    }

    public void setId(final Long id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "{\"BaseEntity\":{"
                + "\"id\":" + id
                + "}}";
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {return false;}
        if (obj == this) {return true;}
        if (obj.getClass() != getClass()) {
            return false;
        }
        if (!(obj instanceof BaseEntity that)) {return false;}
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
