package com.jx.tracker.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock_base")
@Schema(name = "股票基础表")
public class StockBase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String symbol;

    private String name;

    private String market;

    private String exchange;

    private String industry;

    private String status;

    private String dataSource;

    private LocalDateTime lastSyncTime;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
}
