package com.softropic.skillars.infrastructure.message;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO for transfering error message with a list of field errors.
 */
public class ErrorDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String helpCode;
    private final ErrorMsg errorMsg;

    private final List<FieldErrorDto> fieldErrors = new ArrayList<>();

    public ErrorDto(final String helpCode, final ErrorMsg errorMsg) {
        this.helpCode = helpCode;
        this.errorMsg = errorMsg;
    }

    public ErrorDto(final String helpCode, final ErrorMsg errorMsg, final List<FieldErrorDto> fieldErrors) {
        this.helpCode = helpCode;
        this.errorMsg = errorMsg;
        this.fieldErrors.addAll(fieldErrors);
    }

    public void add(final String objectName, final String field, final ErrorMsg errorMsg) {
        fieldErrors.add(new FieldErrorDto(objectName, field, errorMsg));
    }

    public ErrorMsg getErrorMsg() {
        return errorMsg;
    }

    public List<FieldErrorDto> getFieldErrors() {
        return fieldErrors;
    }

    public String getHelpCode() {
        return helpCode;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {return true;}

        if (obj == null || getClass() != obj.getClass()) {return false;}

        final ErrorDto errorDto = (ErrorDto) obj;

        return new EqualsBuilder().append(errorMsg, errorDto.errorMsg)
                                  .append(fieldErrors, errorDto.fieldErrors)
                                  .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37).append(errorMsg).append(fieldErrors).toHashCode();
    }
}
