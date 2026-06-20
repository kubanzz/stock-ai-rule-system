package com.jx.tracker.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

/**
 *
 * @author Benjamin
 * @since 2022/10/25
 */

@Configuration
@ConfigurationProperties(prefix = "remote")
@Data
@Slf4j
public class RemoteServiceConfig {

    /**
     * 认证服务地址
     */
    private String authServiceAddress;

    @PostConstruct
    public void init(){
        log.info("----关联服务配置初始化 RemoteServiceConfig:{}",this);
    }
}
