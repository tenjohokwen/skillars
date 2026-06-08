package com.softropic.skillars.infrastructure.validation;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.softropic.skillars.infrastructure.validation.CamMobileValidator;
import com.softropic.skillars.infrastructure.validation.Provider;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.io.IOException;
import java.io.Serializable;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

@JsonSerialize(using = PhoneNumberDto.PhoneNumberSerializer.class)
@JsonDeserialize(using = PhoneNumberDto.PhoneNumberDeserializer.class)
public class PhoneNumberDto implements Serializable {
    @NotEmpty
    private String phone;

    @NotEmpty
    @Size(min = 2,
          max = 2)
    private String iso2Country;

    private PhoneType phoneType;

    private Provider provider;

    public PhoneNumberDto() {}

    public PhoneNumberDto(String phone, String locale) {
        this.phone = phone;
        this.iso2Country = locale;
        this.phoneType = PhoneType.FIXED;
    }

    public PhoneNumberDto(String phone, Provider provider, String locale) {
        this.phone = phone;
        this.iso2Country = locale;
        this.phoneType = PhoneType.MOBILE;
        this.provider = provider;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getIso2Country() {
        return iso2Country;
    }

    public void setIso2Country(String locale) {
        this.iso2Country = locale;
    }

    public PhoneType getPhoneType() {
        return phoneType;
    }

    public void setPhoneType(PhoneType phoneType) {
        this.phoneType = phoneType;
    }

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {return true;}

        if (!(o instanceof PhoneNumberDto that)) {return false;}

        return new EqualsBuilder().append(phone, that.phone)
                                  .append(iso2Country, that.iso2Country)
                                  .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37).append(phone).append(iso2Country).toHashCode();
    }

    public enum PhoneType {
        FIXED,
        MOBILE
    }

    @Override
    public String toString() {
        return "{\"PhoneNumber\":{"
                + "\"phone\":\"" + phone + "\""
                + ", \"iso2Country\":\"" + iso2Country + "\""
                + ", \"phoneType\":\"" + phoneType + "\""
                + ", \"provider\":\"" + provider + "\""
                + "}}";
    }

    /**
     * Custom Jackson JsonDeserializer for PhoneNumber.
     * Deserializes a string into a PhoneNumber object.
     *
     * Note: This deserializer does NOT perform validation. Validation is handled
     * by the @CamPhone Bean Validation annotation on the DTO field, which allows
     * validation errors to be properly returned as field-level errors in the API response.
     *
     * Example: "677123456" -> PhoneNumber("677123456", null, "CM")
     */
    public static class PhoneNumberDeserializer extends JsonDeserializer<PhoneNumberDto> {
        @Override
        public PhoneNumberDto deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String phoneString = p.getValueAsString(); // Get the JSON string value

            if (phoneString == null) {
                return null;
            }

            // Create a basic PhoneNumberDto with the raw phone string
            // Validation and provider identification will be done by @CamPhone validator
            PhoneNumberDto dto = new PhoneNumberDto();
            dto.setPhone(phoneString.replaceAll("\\s+", "")); // Remove whitespace
            dto.setIso2Country("CM"); // Default to Cameroon
            return dto;
        }
    }

    /**
     * Custom Jackson JsonSerializer for PhoneNumber.
     * Serializes a PhoneNumber object into its 'phone' string value.
     * Example: PhoneNumber("677123456", ...) -> "677123456"
     */
    public static class PhoneNumberSerializer extends JsonSerializer<PhoneNumberDto> {
        @Override
        public void serialize(PhoneNumberDto value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (value != null && value.getPhone() != null) {
                gen.writeString(value.getPhone());
            } else {
                gen.writeNull();
            }
        }
    }
}
