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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("backtest_result")
@Schema(name = "回测结果表")
public class BacktestResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String objectType;

    private String objectCode;

    private LocalDate startDate;

    private LocalDate endDate;

    private Integer triggerCount;

    private BigDecimal winRate;

    private BigDecimal avgReturn;

    private BigDecimal maxDrawdown;

    private BigDecimal sharpeRatio;

    private String resultJson;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
