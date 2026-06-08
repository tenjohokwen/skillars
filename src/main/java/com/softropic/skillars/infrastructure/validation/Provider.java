package com.softropic.skillars.infrastructure.validation;

public enum Provider {
    MTN,
    ORANGE,
    NEXTTEL;
    //CAMTEL  //DO NOT add CAMTEL. A number with type fixed is considered as camtel. A number with a provider is considered as a mobile

    private final boolean mobile;

    Provider(boolean mobile) {this.mobile = mobile;}
    Provider() {this.mobile = true;}

    public static Provider getProvider(String provider) {
        return Provider.valueOf(provider.toUpperCase());
    }

    public boolean isMobile() {
        return this.mobile;
    }
}
