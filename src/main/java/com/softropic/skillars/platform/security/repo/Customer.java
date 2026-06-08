package com.softropic.skillars.platform.security.repo;



import com.softropic.skillars.platform.security.contract.Gender;
import com.softropic.skillars.platform.security.contract.Consumer;
import com.softropic.skillars.infrastructure.persistence.AbstractAuditingEntity;
import com.softropic.skillars.infrastructure.validation.PhoneNumber;

import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.MappedSuperclass;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@MappedSuperclass
//TODO put unique constraint on firstName, lastName, dateOfBirth so that a user is not registered twice (the email may not be sufficient to impose this since user could register with a different one)
public class Customer extends AbstractAuditingEntity implements Consumer {
    @Size(max = 50)
    @Column(name = "first_name", length = 50, columnDefinition = "text", nullable = false)
    protected String firstName;

    @Size(max = 50)
    @Column(name = "last_name", length = 50, columnDefinition = "text", nullable = false)
    protected String lastName;

    @Size(max = 25)
    @Column(name = "title", columnDefinition = "text")
    protected String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false)
    protected Gender gender;

    @Past
    @Column(name = "dob", nullable = false)
    protected LocalDate dateOfBirth;

    @Size(min = 2, max = 5)
    @Column(name = "lang_key", length = 5, columnDefinition = "text")
    protected String langKey;

    //@Phone //TODO enable this. It should have an attribute stating if a null is ok
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name="phone", column=@Column(name="phone", unique = true)),
            @AttributeOverride(name="iso2Country", column=@Column(name = "iso2_country", length = 2, nullable = false)),
            @AttributeOverride(name="phoneType", column=@Column(name = "phone_type"))
    })
    protected PhoneNumber phone;

    @NotNull
    @Email(regexp = "[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,3}", flags = Pattern.Flag.CASE_INSENSITIVE)
    @Size(min = 6, max = 100)
    @Column(length = 100, unique = true, nullable = false, columnDefinition = "text")
    protected String email;

    @ElementCollection(fetch = FetchType.EAGER)
    @Valid
    @Size(max = 3)
    protected Set<Address> addresses = new HashSet<>();

    protected String nationalId;

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Gender getGender() {
        return gender;
    }

    public void setGender(Gender gender) {
        this.gender = gender;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getLangKey() {
        return langKey;
    }

    public void setLangKey(String langKey) {
        this.langKey = langKey;
    }

    public PhoneNumber getPhone() {
        return phone;
    }

    public void setPhone(PhoneNumber phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public @Valid Set<Address> getAddresses() {
        return addresses;
    }

    public void setAddresses(@Valid @NotNull Set<Address> addresses) {
        this.addresses = addresses;
    }

    public void addOrReplaceAddress(@Valid @NotNull Address address) {
        final Iterator<Address> iterator = this.addresses.iterator();
        Address current;
        while(iterator.hasNext()) {
            current = iterator.next();
            if(StringUtils.equals(current.getName(), address.getName())) {
                iterator.remove();
            }
        }
        this.addresses.add(address);
    }

    public String getNationalId() {
        return nationalId;
    }

    public void setNationalId(String nationalId) {
        this.nationalId = nationalId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {return true;}
        if (!(o instanceof Customer customer)) {return false;}
        // If both have nationalId, compare by nationalId (primary business key)
        if (Objects.nonNull(this.nationalId) && Objects.nonNull(customer.nationalId)) {
            return Objects.equals(this.nationalId, customer.nationalId);
        }
        // If neither has nationalId, compare by natural key: firstName + lastName + dateOfBirth
        if (Objects.isNull(this.nationalId) && Objects.isNull(customer.nationalId)) {
            return Objects.equals(firstName, customer.firstName)
                    && Objects.equals(lastName, customer.lastName)
                    && Objects.equals(dateOfBirth, customer.dateOfBirth);
        }
        // One has nationalId and the other doesn't - not equal
        return false;
    }

    @Override
    public int hashCode() {
        // Use a constant hashCode for JPA entities to avoid issues when ID changes
        // This ensures entities work correctly in HashSet/HashMap even when fields change
        return getClass().hashCode();
    }
}
