package com.jx.tracker.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class AuthCompatibilityController {

    private static final String DEV_ACCESS_TOKEN = "stock-dev-access-token";

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody(required = false) LoginRequest request) {
        return success(Map.of(
                "accessToken", DEV_ACCESS_TOKEN
        ));
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout() {
        return success(true);
    }

    @GetMapping("/auth/codes")
    public Map<String, Object> accessCodes() {
        return success(List.of("AC_STOCK_SIGNAL", "AC_STOCK_RULE", "AC_STOCK_BACKTEST"));
    }

    @GetMapping("/user/info")
    public Map<String, Object> userInfo() {
        return success(Map.of(
                "avatar", "https://unpkg.com/@vbenjs/static-source@0.1.7/source/avatar-v1.webp",
                "desc", "股票因子规则预测与 AI 规则优化系统开发账号",
                "homePath", "/stock/signals",
                "realName", "股票规则管理员",
                "roles", List.of("super"),
                "token", DEV_ACCESS_TOKEN,
                "userId", "stock-admin",
                "username", "admin"
        ));
    }

    private Map<String, Object> success(Object data) {
        return Map.of(
                "code", 0,
                "data", data,
                "msg", "ok"
        );
    }

    public record LoginRequest(String username, String password) {
    }
}
