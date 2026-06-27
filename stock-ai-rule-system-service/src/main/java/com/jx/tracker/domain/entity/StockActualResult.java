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
@TableName("stock_actual_result")
@Schema(name = "实际表现表")
public class StockActualResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String symbol;

    private LocalDate signalDate;

    private BigDecimal return1d;

    private BigDecimal return3d;

    private BigDecimal return5d;

    private BigDecimal return10d;

    private Boolean hit1d;

    private Boolean hit5d;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdTime;
}
