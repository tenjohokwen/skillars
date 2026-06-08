package com.softropic.skillars.platform.security.contract;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.infrastructure.validation.CamPhone;
import com.softropic.skillars.platform.security.api.dto.AddressDto;

import java.time.LocalDate;
import java.util.Set;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A DTO representing a user, with his authorities.
 */
public class UserDto {

    public static final int PASSWORD_MIN_LENGTH = 5;
    public static final int PASSWORD_MAX_LENGTH = 100;

    //@Email(regexp = "[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,3}", flags = Pattern.Flag.CASE_INSENSITIVE)
    //@Size(min = 5, max = 100)
    private String login;

    private LoginIdType loginIdType;

    @NotNull
    @Size(min = PASSWORD_MIN_LENGTH, max = PASSWORD_MAX_LENGTH)
    private String password;

    @Size(max = 25)
    private String title;

    @Size(max = 50)
    private String firstName;

    @Size(max = 50)
    private String lastName;

    @NotNull
    @Email(regexp = "[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,3}", flags = Pattern.Flag.CASE_INSENSITIVE)
    @Size(min = 5, max = 100)
    private String email;

    @NotNull
    @CamPhone
    private String phone;

    private boolean activated = false;

    @Size(min = 2, max = 5)
    private String langKey = "en";

    private Set<String> authorities;

    @NotNull
    private Gender gender;

    @Size(min = 5, max = 50)
    private String nationalId;

    @Past
    @JsonFormat(pattern="yyyy-MM-dd")
    private LocalDate dob;

    private boolean otpEnabled;

    private AddressDto address;

    public UserDto() {
    }

    public @Size(min = 1,
                          max = 50) String getLogin() {
        return login;
    }

    public void setLogin(@Size(min = 1,
                                        max = 50) String login) {
        this.login = login;
    }

    public LoginIdType getLoginIdType() {
        return loginIdType;
    }

    public void setLoginIdType(LoginIdType loginIdType) {
        this.loginIdType = loginIdType;
    }

    public @NotNull @Size(min = PASSWORD_MIN_LENGTH,
                          max = PASSWORD_MAX_LENGTH) String getPassword() {
        return password;
    }

    public void setPassword(@NotNull @Size(min = PASSWORD_MIN_LENGTH,
                                           max = PASSWORD_MAX_LENGTH) String password) {
        this.password = password;
    }

    public @Size(max = 25) String getTitle() {
        return title;
    }

    public void setTitle(@Size(max = 25) String title) {
        this.title = title;
    }

    public @Size(max = 50) String getFirstName() {
        return firstName;
    }

    public void setFirstName(@Size(max = 50) String firstName) {
        this.firstName = firstName;
    }

    public @Size(max = 50) String getLastName() {
        return lastName;
    }

    public void setLastName(@Size(max = 50) String lastName) {
        this.lastName = lastName;
    }

    public @Email @Size(min = 5,
                        max = 100) String getEmail() {
        return email;
    }

    public void setEmail(@Email @Size(min = 5,
                                      max = 100) String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public boolean isActivated() {
        return activated;
    }

    public void setActivated(boolean activated) {
        this.activated = activated;
    }

    public @Size(min = 2,
                 max = 5) String getLangKey() {
        return langKey;
    }

    public void setLangKey(@Size(min = 2,
                                 max = 5) String langKey) {
        this.langKey = langKey;
    }

    public Set<String> getAuthorities() {
        return authorities;
    }

    public void setAuthorities(Set<String> authorities) {
        this.authorities = authorities;
    }

    public Gender getGender() {
        return gender;
    }

    public void setGender(Gender gender) {
        this.gender = gender;
    }

    public String getNationalId() {
        return nationalId;
    }

    public void setNationalId(String nationalId) {
        this.nationalId = nationalId;
    }

    public @Past LocalDate getDob() {
        return dob;
    }

    public void setDob(@Past LocalDate dob) {
        this.dob = dob;
    }

    public boolean isOtpEnabled() {
        return otpEnabled;
    }

    public void setOtpEnabled(boolean otpEnabled) {
        this.otpEnabled = otpEnabled;
    }

    public AddressDto getAddress() {
        return address;
    }

    public void setAddress(AddressDto address) {
        this.address = address;
    }

    @Override
    public String toString() {
        return "{\"UserDTO\":{"
                + "\"login\":\"" + login + "\""
                + ", \"password\":\"" + password + "\""
                + ", \"title\":\"" + title + "\""
                + ", \"firstName\":\"" + firstName + "\""
                + ", \"lastName\":\"" + lastName + "\""
                + ", \"email\":\"" + email + "\""
                + ", \"phone\":\"" + phone + "\""
                + ", \"activated\":\"" + activated + "\""
                + ", \"langKey\":\"" + langKey + "\""
                + ", \"authorities\":" + authorities
                + ", \"gender\":\"" + gender + "\""
                + ", \"dob\":" + dob
                + ", \"otpEnabled\":\"" + otpEnabled + "\""
                + "}}";
    }
}
