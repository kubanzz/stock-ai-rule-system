package com.jx.tracker.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jx.tracker.common.BCryptTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import org.apache.ibatis.type.JdbcType;

import java.time.LocalDateTime;

/**
 * 用户表
 *
 * @author Benjamin
 * @since 2025-05-08
 */
@EqualsAndHashCode(callSuper = false)
@TableName("users")
@Data
@Schema(name = "用户表")
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Users {

    @TableId(type = IdType.AUTO)
    private Integer id;

    @Schema(description = "企业微信Code")
    private String qwCode;

    @Schema(description = "用户密码")
    @TableField(value = "password", jdbcType = JdbcType.VARCHAR, typeHandler = BCryptTypeHandler.class)
    @JsonIgnore
    private String password;

    @Schema(description = "用户姓名")
    private String name;

    @Schema(description = "用户所在部门")
    private String departmentName;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private LocalDateTime createdTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private LocalDateTime updatedTime;
}