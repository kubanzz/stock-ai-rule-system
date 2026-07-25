package com.jx.tracker.risk.persistence.mapper;

import com.jx.tracker.risk.persistence.entity.RiskProvisionalObservationRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface RiskProvisionalObservationMapper {

    String ACTUAL_VALUE = """
            COALESCE(
                o.indicator_value,
                CASE
                    WHEN JSON_TYPE(JSON_EXTRACT(o.payload_json, '$.auditValue'))
                         IN ('INTEGER', 'DOUBLE')
                    THEN CAST(
                        JSON_UNQUOTE(JSON_EXTRACT(o.payload_json, '$.auditValue'))
                        AS DECIMAL(30, 10)
                    )
                END
            )
            """;

    @Select("""
            SELECT o.object_type, o.object_id, o.horizon, o.trade_date,
                   o.dimension_code, o.indicator_code, o.component_code,
                   """ + ACTUAL_VALUE + """
                   AS actual_value,
                   o.observed_at, o.available_at, o.source, o.quality_status
            FROM risk_indicator_observation o
            WHERE o.object_type = #{objectType}
              AND o.object_id = #{objectId}
              AND o.horizon = #{horizon}
              AND o.trade_date BETWEEN #{startDate} AND #{tradeDate}
              AND o.available_at <= #{asOf}
              AND o.indicator_code <> 'DATA_SW1_MEMBERSHIP'
              AND (
                  """ + ACTUAL_VALUE + """
              ) IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1
                  FROM risk_indicator_observation newer
                  WHERE newer.object_type = o.object_type
                    AND newer.object_id = o.object_id
                    AND newer.horizon = o.horizon
                    AND newer.trade_date = o.trade_date
                    AND newer.indicator_code = o.indicator_code
                    AND newer.component_code = o.component_code
                    AND newer.available_at <= #{asOf}
                    AND COALESCE(
                        newer.indicator_value,
                        CASE
                            WHEN JSON_TYPE(JSON_EXTRACT(
                                newer.payload_json, '$.auditValue'
                            )) IN ('INTEGER', 'DOUBLE')
                            THEN CAST(
                                JSON_UNQUOTE(JSON_EXTRACT(
                                    newer.payload_json, '$.auditValue'
                                )) AS DECIMAL(30, 10)
                            )
                        END
                    ) IS NOT NULL
                    AND (newer.available_at > o.available_at
                      OR (newer.available_at = o.available_at AND newer.id > o.id))
              )
            ORDER BY o.indicator_code, o.component_code, o.trade_date
            """)
    List<RiskProvisionalObservationRow> selectHistory(
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("horizon") String horizon,
            @Param("startDate") LocalDate startDate,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("asOf") LocalDateTime asOf
    );

    @Select("""
            SELECT o.object_type, o.object_id, o.horizon, o.trade_date,
                   o.dimension_code, o.indicator_code, o.component_code,
                   """ + ACTUAL_VALUE + """
                   AS actual_value,
                   o.observed_at, o.available_at, o.source, o.quality_status
            FROM risk_indicator_observation o
            WHERE o.object_type = 'sector'
              AND o.horizon = #{horizon}
              AND o.trade_date = #{tradeDate}
              AND o.available_at <= #{asOf}
              AND o.indicator_code <> 'DATA_SW1_MEMBERSHIP'
              AND (
                  """ + ACTUAL_VALUE + """
              ) IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1
                  FROM risk_indicator_observation newer
                  WHERE newer.object_type = o.object_type
                    AND newer.object_id = o.object_id
                    AND newer.horizon = o.horizon
                    AND newer.trade_date = o.trade_date
                    AND newer.indicator_code = o.indicator_code
                    AND newer.component_code = o.component_code
                    AND newer.available_at <= #{asOf}
                    AND COALESCE(
                        newer.indicator_value,
                        CASE
                            WHEN JSON_TYPE(JSON_EXTRACT(
                                newer.payload_json, '$.auditValue'
                            )) IN ('INTEGER', 'DOUBLE')
                            THEN CAST(
                                JSON_UNQUOTE(JSON_EXTRACT(
                                    newer.payload_json, '$.auditValue'
                                )) AS DECIMAL(30, 10)
                            )
                        END
                    ) IS NOT NULL
                    AND (newer.available_at > o.available_at
                      OR (newer.available_at = o.available_at AND newer.id > o.id))
              )
            ORDER BY o.indicator_code, o.component_code, o.object_id
            """)
    List<RiskProvisionalObservationRow> selectSectorCrossSection(
            @Param("horizon") String horizon,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("asOf") LocalDateTime asOf
    );
}
