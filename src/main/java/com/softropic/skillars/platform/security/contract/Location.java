package com.softropic.skillars.platform.security.contract;

public interface Location {
     String getCompanyName();

     void setCompanyName(String companyName);

     String getStreet();

     void setStreet(String addressLine1);

     String getAddressLine2();

     void setAddressLine2(String addressLine2);

     String getAddressLine1();

     void setAddressLine1(String addressLine1);

     String getCity() ;

     void setCity(String city);

     String getStateProvince();

     void setStateProvince(String stateProvince);

     String getPostalCode();

     void setPostalCode(String postalCode);

     String getCountry();

     void setCountry(String country);

     String getName();

     void setName(String name);

}
