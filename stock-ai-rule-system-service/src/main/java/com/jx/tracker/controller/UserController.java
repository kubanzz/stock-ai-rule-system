package com.jx.tracker.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.domain.dto.AuthUserDto;
import com.jx.tracker.domain.entity.Users;
import com.jx.tracker.service.IUserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * 用户注册
 *
 * @author Benjamin
 * @since 2025-05-08
 */
@RestController
@RequestMapping("/user")
@Tag(name = "用户注册验证")
public class UserController {

    @Resource
    private IUserService userService;

    @PostMapping("/oauth/{code}")
    @Operation(summary = "企业微信验证")
    public ResponseEntity<AuthUserDto> validateTicket(@PathVariable String code) {
        AuthUserDto authUserDto = userService.validateTicket(code);
        return ResponseEntity.ok(authUserDto);
    }

    @PostMapping("/register")
    public ResponseEntity<Boolean> registerUser(@RequestBody Users user) {
        return ResponseEntity.ok(userService.registerUser(user));
    }

    @GetMapping("/users")
    public AjaxResult getUser(@RequestParam String userId) {
        return AjaxResult.success(userService.getUser(userId));
    }
}