package com.softropic.skillars.platform.security.contract;



import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.infrastructure.validation.PhoneNumber;
import com.softropic.skillars.platform.security.repo.Address;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Set;

/**
 * Needed to represent a user without login data.
 * JPA also uses it as read-only for queries
 */
public interface Consumer extends Serializable {
    Long getId();
    String getFirstName();
    String getLastName();
    String getTitle();
    Gender getGender();
    LocalDate getDateOfBirth();
    String getLangKey();
    PhoneNumber getPhone();
    String getEmail() ;
    Set<Address> getAddresses();
}
