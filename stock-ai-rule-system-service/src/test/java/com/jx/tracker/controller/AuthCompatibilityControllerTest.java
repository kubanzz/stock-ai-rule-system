package com.jx.tracker.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Test
    void apiPrefixedAuthRoutesMatchFrontendBaseUrl() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").value("stock-dev-access-token"));
        mockMvc.perform(get("/api/auth/codes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("AC_STOCK_SIGNAL"));
        mockMvc.perform(get("/api/user/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.homePath").value("/stock/signals"));
    }
}
