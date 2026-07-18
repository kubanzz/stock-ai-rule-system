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
@TableName("stock_signal_daily")
@Schema(name = "信号结果表")
public class StockSignalDaily {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String symbol;

    private LocalDate signalDate;

    @TableField("`signal`")
    private String signal;

    private String signalDirection;

    private String signalLevel;

    private BigDecimal bullishScore;

    private BigDecimal bearishScore;

    private BigDecimal riskScore;

    private BigDecimal confidence;

    private String triggeredRules;

    private String explanation;

    private String riskDisclaimer;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
