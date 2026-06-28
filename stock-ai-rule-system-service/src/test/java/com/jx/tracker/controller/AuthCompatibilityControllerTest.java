package com.jx.tracker.controller;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthCompatibilityControllerTest {

    private final AuthCompatibilityController controller = new AuthCompatibilityController();

    @Test
    void loginReturnsVbenAccessTokenContract() {
        Map<String, Object> response = controller.login(new AuthCompatibilityController.LoginRequest("admin", "123456"));

        assertThat(response.get("code")).isEqualTo(0);
        assertThat(response.get("data")).isInstanceOf(Map.class);
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertThat(data.get("accessToken")).isEqualTo("stock-dev-access-token");
    }

    @Test
    void userInfoReturnsVbenUserContract() {
        Map<String, Object> response = controller.userInfo();

        assertThat(response.get("code")).isEqualTo(0);
        assertThat(response.get("data")).isInstanceOf(Map.class);
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertThat(data.get("username")).isEqualTo("admin");
        assertThat(data.get("realName")).isEqualTo("股票规则管理员");
        assertThat(data.get("homePath")).isEqualTo("/stock/signals");
        assertThat(data.get("token")).isEqualTo("stock-dev-access-token");
        assertThat(data.get("roles")).isEqualTo(List.of("super"));
    }

    @Test
    void accessCodesReturnVbenArrayContract() {
        Map<String, Object> response = controller.accessCodes();

        assertThat(response.get("code")).isEqualTo(0);
        assertThat(response.get("data")).isEqualTo(List.of("AC_STOCK_SIGNAL", "AC_STOCK_RULE", "AC_STOCK_BACKTEST"));
    }
}
