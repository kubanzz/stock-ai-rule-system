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

    public static final String LEGACY_STRATEGY_CODE = "LEGACY";
    public static final String LEGACY_STRATEGY_VERSION = "legacy";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String symbol;

    private LocalDate signalDate;

    /** 冻结的生成方案身份；旧记录不能据当前启用方案反推归属。 */
    @Builder.Default
    private String strategyCode = LEGACY_STRATEGY_CODE;

    /** 信号生成时的方案名称，避免后续重命名改写历史显示。 */
    private String strategyName;

    @Builder.Default
    private String strategyVersion = LEGACY_STRATEGY_VERSION;

    /** regular = 当期生成；backfill = 使用现行规则对历史交易日补算。 */
    private String generationType;

    private LocalDateTime generatedAt;

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

    private String traceJson;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
