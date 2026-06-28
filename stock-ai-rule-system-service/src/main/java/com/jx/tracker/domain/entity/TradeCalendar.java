package com.jx.tracker.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("trade_calendar")
@Schema(name = "交易日历表")
public class TradeCalendar {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String market;

    private LocalDate tradeDate;

    @TableField("is_open")
    private Boolean open;

    private LocalDate preTradeDate;

    private LocalDate nextTradeDate;

    private String dataSource;

    private LocalDateTime syncTime;
}
