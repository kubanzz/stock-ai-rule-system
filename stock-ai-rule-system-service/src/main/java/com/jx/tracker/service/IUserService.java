package com.jx.tracker.service;

import com.jx.tracker.domain.dto.AuthUserDto;
import com.jx.tracker.domain.entity.Users;

/**
 * 用户管理业务层
 *
 * @author Benjamin
 * @since 2025-05-08
 */
public interface IUserService {

    /**
     * 企业微信验证及信息获取
     *
     * @param code 企业微信Code
     */
    AuthUserDto validateTicket(String code);

    /**
     * 注册用户
     */
    Boolean registerUser(Users users);

    Users getUser(String userId);
}
