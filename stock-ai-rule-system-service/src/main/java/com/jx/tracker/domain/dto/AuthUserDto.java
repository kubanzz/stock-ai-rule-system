package com.jx.tracker.domain.dto;

import lombok.Data;

/**
 * 企业微信用户信息
 *
 * @author Benjamin
 * @since 2025-05-08
 */
@Data
public class AuthUserDto {

    private String access_token;
    private String userName;
    private String nickname;
    private String email;
    private String bizMail;
    private Integer gender;
    private Long leader;
    private String avatar;
    private Long deptId;
    private int status;
}
