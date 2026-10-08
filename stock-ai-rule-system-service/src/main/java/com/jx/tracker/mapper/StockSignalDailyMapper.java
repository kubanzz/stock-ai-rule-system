package com.jx.tracker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jx.tracker.domain.entity.StockSignalDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface StockSignalDailyMapper extends BaseMapper<StockSignalDaily> {

    /** 看板只读取每股每方案的最新信号，不载入历史明细及大型推理审计 JSON。 */
    @Select("""
            <script>
            SELECT signal_record.id, signal_record.symbol, signal_record.signal_date,
                   signal_record.strategy_code, signal_record.strategy_version, signal_record.strategy_name,
                   signal_record.generation_type, signal_record.generated_at, signal_record.`signal`,
                   signal_record.signal_direction, signal_record.signal_level,
                   signal_record.bullish_score, signal_record.bearish_score, signal_record.risk_score,
                   signal_record.confidence, signal_record.triggered_rules,
                   signal_record.created_at AS createdTime
            FROM stock_signal_daily signal_record
            JOIN (
                SELECT symbol, strategy_code, strategy_version, MAX(signal_date) AS signal_date
                FROM stock_signal_daily
                WHERE signal_date &lt;= #{tradeDate} AND symbol IN
                  <foreach collection="symbols" item="symbol" open="(" separator="," close=")">
                    #{symbol}
                  </foreach>
                  <if test="strategyCode != null">AND strategy_code = #{strategyCode}</if>
                  <if test="strategyVersion != null">AND strategy_version = #{strategyVersion}</if>
                GROUP BY symbol, strategy_code, strategy_version
            ) latest ON latest.symbol = signal_record.symbol
                AND latest.strategy_code = signal_record.strategy_code
                AND latest.strategy_version = signal_record.strategy_version
                AND latest.signal_date = signal_record.signal_date
            </script>
            """)
    List<StockSignalDaily> selectLatestForDashboard(@Param("symbols") List<String> symbols,
                                                  @Param("tradeDate") LocalDate tradeDate,
                                                  @Param("strategyCode") String strategyCode,
                                                  @Param("strategyVersion") String strategyVersion);

    @Insert("""
            INSERT INTO stock_signal_daily (
                symbol, signal_date, strategy_code, strategy_version, strategy_name, generation_type, generated_at, `signal`, signal_direction, signal_level,
                bullish_score, bearish_score, risk_score, confidence, triggered_rules,
                explanation, risk_disclaimer, trace_json
            ) VALUES (
                #{signalRecord.symbol}, #{signalRecord.signalDate},
                COALESCE(#{signalRecord.strategyCode}, 'LEGACY'),
                COALESCE(#{signalRecord.strategyVersion}, 'legacy'),
                #{signalRecord.strategyName},
                #{signalRecord.generationType}, #{signalRecord.generatedAt},
                #{signalRecord.signal}, #{signalRecord.signalDirection}, #{signalRecord.signalLevel},
                #{signalRecord.bullishScore}, #{signalRecord.bearishScore}, #{signalRecord.riskScore},
                #{signalRecord.confidence}, #{signalRecord.triggeredRules}, #{signalRecord.explanation},
                #{signalRecord.riskDisclaimer}, #{signalRecord.traceJson}
            ) ON DUPLICATE KEY UPDATE
                strategy_name = VALUES(strategy_name),
                `signal` = VALUES(`signal`),
                generation_type = VALUES(generation_type),
                generated_at = VALUES(generated_at),
                signal_direction = VALUES(signal_direction),
                signal_level = VALUES(signal_level),
                bullish_score = VALUES(bullish_score),
                bearish_score = VALUES(bearish_score),
                risk_score = VALUES(risk_score),
                confidence = VALUES(confidence),
                triggered_rules = VALUES(triggered_rules),
                explanation = VALUES(explanation),
                risk_disclaimer = VALUES(risk_disclaimer),
                trace_json = VALUES(trace_json)
            """)
    int upsertSignal(@Param("signalRecord") StockSignalDaily signal);

    @Insert("""
            INSERT IGNORE INTO stock_signal_daily (
                symbol, signal_date, strategy_code, strategy_version, strategy_name, generation_type, generated_at, `signal`, signal_direction, signal_level,
                bullish_score, bearish_score, risk_score, confidence, triggered_rules,
                explanation, risk_disclaimer, trace_json
            ) VALUES (
                #{signalRecord.symbol}, #{signalRecord.signalDate},
                COALESCE(#{signalRecord.strategyCode}, 'LEGACY'),
                COALESCE(#{signalRecord.strategyVersion}, 'legacy'),
                #{signalRecord.strategyName},
                #{signalRecord.generationType}, #{signalRecord.generatedAt},
                #{signalRecord.signal}, #{signalRecord.signalDirection}, #{signalRecord.signalLevel},
                #{signalRecord.bullishScore}, #{signalRecord.bearishScore}, #{signalRecord.riskScore},
                #{signalRecord.confidence}, #{signalRecord.triggeredRules},
                #{signalRecord.explanation}, #{signalRecord.riskDisclaimer}, #{signalRecord.traceJson}
            )
            """)
    int insertSignalIfAbsent(@Param("signalRecord") StockSignalDaily signal);

    @Insert("""
            INSERT INTO stock_signal_daily_history (
                signal_id, version_no, symbol, signal_date, strategy_code, strategy_version, strategy_name, `signal`, signal_direction, signal_level,
                bullish_score, bearish_score, risk_score, confidence, triggered_rules,
                explanation, risk_disclaimer, trace_json, content_fingerprint, available_at
            )
            SELECT current_signal.id, COALESCE(latest.version_no, 0) + 1,
                   current_signal.symbol, current_signal.signal_date,
                   current_signal.strategy_code, current_signal.strategy_version, current_signal.strategy_name,
                   current_signal.`signal`,
                   current_signal.signal_direction, current_signal.signal_level,
                   current_signal.bullish_score, current_signal.bearish_score,
                   current_signal.risk_score, current_signal.confidence,
                   current_signal.triggered_rules, current_signal.explanation,
                   current_signal.risk_disclaimer, current_signal.trace_json,
                   current_signal.signal_content_fingerprint,
                   #{availableAt}
            FROM stock_signal_daily current_signal
            LEFT JOIN stock_signal_daily_history latest
              ON latest.id = (
                  SELECT previous.id
                  FROM stock_signal_daily_history previous
                  WHERE previous.signal_id = current_signal.id
                  ORDER BY previous.version_no DESC
                  LIMIT 1
              )
            WHERE current_signal.id = #{signalId}
              AND (latest.id IS NULL
                   OR latest.content_fingerprint <> current_signal.signal_content_fingerprint)
            """)
    int insertSignalHistoryIfChanged(@Param("signalId") Long signalId,
                                     @Param("availableAt") LocalDateTime availableAt);
}
