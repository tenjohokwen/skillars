package com.softropic.skillars.platform.security.repo;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;


@Embeddable
public class Address implements Serializable {

    //The name that identifies the address (e.g. HOME, WORK)
    @NotNull
    @Size(min = 4, max = 50)
    @Column(name = "name", nullable = false)
    protected String name;

    @Size(max = 250)
    @Column(name = "company_name")
    protected String companyName;

    @Size(max = 250)
    @Column(name = "address_line1", nullable = false)
    protected String addressLine1;

    @Size(max = 250)
    @Column(name = "address_line2")
    protected String addressLine2;

    @Size(max = 250)
    @Column(name = "address_line3")
    protected String addressLine3;

    @Size(max = 50)
    @Column(name = "city", nullable = false)
    protected String city;

    @Size(max = 50)
    @Column(name = "state_prov", nullable = false)
    protected String stateProvince;

    @Size(max = 25)
    @Column(name = "postal_code", nullable = false)
    protected String postalCode;

    @Size(max = 25)
    @Column(name = "country", nullable = false)
    protected String country;

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getAddressLine1() {
        return addressLine1;
    }

    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    public String getAddressLine2() {
        return addressLine2;
    }

    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    public String getAddressLine3() {
        return addressLine3;
    }

    public void setAddressLine3(String addressLine3) {
        this.addressLine3 = addressLine3;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getStateProvince() {
        return stateProvince;
    }

    public void setStateProvince(String stateProvince) {
        this.stateProvince = stateProvince;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {return true;}

        if (!(o instanceof Address address)) {return false;}

        return new EqualsBuilder().append(name, address.name).isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37).append(name).toHashCode();
    }
}
