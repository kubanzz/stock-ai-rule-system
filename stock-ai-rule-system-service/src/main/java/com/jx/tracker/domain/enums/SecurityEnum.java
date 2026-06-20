package com.jx.tracker.domain.enums;

/**
 * 安全相关常量
 *
 * @author Benjamin
 * @since 2025-09-08
 */
public enum SecurityEnum {

    /**
     * 存在与header中的token参数头 名
     */
    HEADER_TOKEN("accessToken"), HEADER_ID_TOKEN("idToken") ,USER_CONTEXT("userContext");

    String value;

    SecurityEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
