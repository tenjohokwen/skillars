package com.softropic.skillars.platform.security.api.dto;

import com.softropic.skillars.platform.security.contract.Gender;
import jakarta.validation.constraints.Size;

/**
 * DTO for user information update requests.
 * All fields are optional - only provided fields will be updated.
 */
public class UpdateUserInfoDto {

    @Size(max = 50)
    private String firstName;

    @Size(max = 50)
    private String lastName;

    @Size(min = 2, max = 5)
    private String langKey;

    @Size(min = 5, max = 50)
    private String nationalId;

    private Gender gender;

    @Size(max = 25)
    private String title;

    public UpdateUserInfoDto() {}

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getLangKey() {
        return langKey;
    }

    public void setLangKey(String langKey) {
        this.langKey = langKey;
    }

    public String getNationalId() {
        return nationalId;
    }

    public void setNationalId(String nationalId) {
        this.nationalId = nationalId;
    }

    public Gender getGender() {
        return gender;
    }

    public void setGender(Gender gender) {
        this.gender = gender;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
