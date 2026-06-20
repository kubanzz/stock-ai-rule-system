package com.jx.tracker.service.impl;

import cn.hutool.http.HttpStatus;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jx.tracker.config.RemoteServiceConfig;
import com.jx.tracker.constant.QwResponseConstants;
import com.jx.tracker.domain.dto.AuthUserDto;
import com.jx.tracker.domain.entity.Users;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.UsersMapper;
import com.jx.tracker.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 用户管理业务层
 *
 * @author Benjamin
 * @since 2025-05-08
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UsersMapper, Users> implements IUserService {
    private static final String TICKET_NAME = "code";
    /**
     * 扫码登录返回的code
     */
    private static final String OAUTH_VALIDATE_TICKET_API = "/oauth/validateTicket";

    @Resource
    private RemoteServiceConfig remoteServiceConfig;

    @Override
    public AuthUserDto validateTicket(String code) {
        log.info("------validateTicket------token:{}", code);
        Map<String, Object> param = new HashMap<>();
        param.put(TICKET_NAME, code);

        log.info("------请求验证token地址：{}", remoteServiceConfig.getAuthServiceAddress() + OAUTH_VALIDATE_TICKET_API);
        String body = HttpUtil.createGet(remoteServiceConfig.getAuthServiceAddress() + OAUTH_VALIDATE_TICKET_API).form(param).execute().body();

        log.info("认证服务进行身份票据认证结果:{}", body);
        JSONObject jsonObject = JSONUtil.parseObj(body);
        if (jsonObject.getInt(QwResponseConstants.RESPONSE_CODE) != HttpStatus.HTTP_OK) {
            throw new RuntimeException("认证失败,oauth认证code无法匹配! " + jsonObject.getStr(QwResponseConstants.RESPONSE_MSG));
        }
        return JSONUtil.toBean(jsonObject.getJSONObject(QwResponseConstants.RESPONSE_DATA), AuthUserDto.class);
    }

    @Override
    public Boolean registerUser(Users user) {
        boolean existUser = this.exists(
                Wrappers.<Users>lambdaQuery()
                        .eq(Users::getQwCode, user.getQwCode())
        );

        Users newUser = Users.builder()
                .departmentName(user.getDepartmentName())
                .qwCode(user.getQwCode())
                .password(user.getPassword())
                .name(user.getName())
                .build();

        if (existUser) {
            return this.update(newUser,
                    Wrappers.<Users>lambdaUpdate()
                            .eq(Users::getQwCode, user.getQwCode())
            );
        } else {
            AuthUserDto authUserDto = this.validateTicket(user.getQwCode());
            if (authUserDto == null) {
                throw new ServiceException("用户企业微信code校验失败");
            }

            return this.save(newUser);
        }
    }

    @Override
    public Users getUser(String userId) {
        return this.getById(userId);
    }
}