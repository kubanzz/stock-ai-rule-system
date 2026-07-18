package com.jx.tracker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jx.tracker.domain.entity.StockSignalDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface StockSignalDailyMapper extends BaseMapper<StockSignalDaily> {

    @Insert("""
            INSERT INTO stock_signal_daily_history (
                signal_id, symbol, signal_date, `signal`, signal_direction, signal_level,
                bullish_score, bearish_score, risk_score, confidence, triggered_rules,
                explanation, risk_disclaimer, available_at
            ) VALUES (
                #{signalRecord.id}, #{signalRecord.symbol}, #{signalRecord.signalDate},
                #{signalRecord.signal}, #{signalRecord.signalDirection}, #{signalRecord.signalLevel},
                #{signalRecord.bullishScore}, #{signalRecord.bearishScore}, #{signalRecord.riskScore},
                #{signalRecord.confidence}, #{signalRecord.triggeredRules}, #{signalRecord.explanation},
                #{signalRecord.riskDisclaimer}, #{availableAt}
            )
            """)
    int insertSignalHistory(@Param("signalRecord") StockSignalDaily signal,
                            @Param("availableAt") LocalDateTime availableAt);
}
