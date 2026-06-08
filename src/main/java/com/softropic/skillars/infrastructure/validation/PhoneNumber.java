package com.softropic.skillars.infrastructure.validation;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;


@Embeddable
public class PhoneNumber implements Serializable {
    @NotEmpty
    @Column(unique = true)
    private String phone;

    @NotEmpty
    @Size(min = 2,
          max = 2)
    @Column(name = "iso2_country",
            length = 2)
    private String iso2Country;

    @Enumerated(EnumType.STRING)
    private PhoneType phoneType;

    @Enumerated(EnumType.STRING)
    private Provider provider;

    public PhoneNumber() {}

    public PhoneNumber(String phone, String locale) {
        this.phone = phone;
        this.iso2Country = locale;
        this.phoneType = PhoneType.FIXED;
    }

    public PhoneNumber(String phone, Provider provider, String locale) {
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

        if (!(o instanceof PhoneNumber that)) {return false;}

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
}
