package com.softropic.skillars.infrastructure.persistence;

import java.util.List;

public enum IdType {
    PASSPORT(List.of("PASSPORT", "PASS")),
    ID_CARD(List.of("ID_CARD", "ID", "NATIONAL_ID")),
    DRIVING_LICENSE(List.of("DRIVING_LICENSE", "DRIVERS_LICENSE"));

    private final List<String> aliases;

    IdType(List<String> aliases) {this.aliases = aliases;}

    public static IdType fromString(String value) {
        for (IdType idType : IdType.values()) {
            if (idType.aliases.contains(value.toUpperCase())) {
                return idType;
            }
        }
        throw new IllegalArgumentException("No enum constant with aliases containing " + value);
    }


}
