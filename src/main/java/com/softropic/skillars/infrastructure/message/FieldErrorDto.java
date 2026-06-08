package com.softropic.skillars.infrastructure.message;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.io.Serial;
import java.io.Serializable;

public class FieldErrorDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String objectName;

    private final String field;

    private final ErrorMsg errorMsg;


    public FieldErrorDto(final String dto, final String field, final ErrorMsg errorMsg) {
        this.objectName = dto;
        this.field = field;
        this.errorMsg = errorMsg;
    }

    public String getObjectName() {
        return objectName;
    }

    public String getField() {
        return field;
    }

    public ErrorMsg getErrorMsg() {
        return errorMsg;
    }


    @Override
    public boolean equals(Object obj) {
        if (obj == null) {return false;}
        if (obj == this) {return true;}
        if (obj.getClass() != getClass()) {
            return false;
        }
        FieldErrorDto rhs = (FieldErrorDto) obj;
        return new EqualsBuilder()
                .append(this.objectName, rhs.objectName)
                .append(this.field, rhs.field)
                .append(this.errorMsg, rhs.errorMsg)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(objectName)
                .append(field)
                .append(errorMsg)
                .toHashCode();
    }
}
