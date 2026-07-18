package com.jx.tracker.risk.model;

import java.util.Arrays;
import java.util.List;

interface RiskWireCode {

    String getCode();

    static <E extends Enum<E> & RiskWireCode> E fromCode(Class<E> type, String code) {
        if (code == null) {
            throw new IllegalArgumentException(type.getSimpleName() + " code must not be null");
        }
        return Arrays.stream(type.getEnumConstants())
                .filter(value -> value.getCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported " + type.getSimpleName() + " code: " + code
                ));
    }

    static <E extends Enum<E> & RiskWireCode> List<String> codes(Class<E> type) {
        return Arrays.stream(type.getEnumConstants()).map(RiskWireCode::getCode).toList();
    }
}
