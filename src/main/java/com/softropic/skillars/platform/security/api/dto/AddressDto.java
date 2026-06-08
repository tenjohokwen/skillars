package com.softropic.skillars.platform.security.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTO for address update requests.
 * Field constraints match the Address entity.
 */
public class AddressDto {

    /**
     * Identifies the address (e.g., "HOME", "WORK").
     */
    @NotNull
    @Size(min = 4, max = 50)
    private String name;

    @Size(max = 250)
    private String companyName;

    @NotNull
    @Size(max = 250)
    private String addressLine1;

    @Size(max = 250)
    private String addressLine2;

    @Size(max = 250)
    private String addressLine3;

    @NotNull
    @Size(max = 50)
    private String city;

    @NotNull
    @Size(max = 50)
    private String stateProvince;

    @NotNull
    @Size(max = 25)
    private String postalCode;

    @NotNull
    @Size(max = 25)
    private String country;

    public AddressDto() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

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
}
