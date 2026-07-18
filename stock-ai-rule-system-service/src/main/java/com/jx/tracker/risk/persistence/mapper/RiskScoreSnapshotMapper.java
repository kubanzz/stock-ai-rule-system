package com.jx.tracker.risk.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jx.tracker.risk.persistence.entity.RiskScoreSnapshotEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface RiskScoreSnapshotMapper extends BaseMapper<RiskScoreSnapshotEntity> {

    @Select("""
            <script>
            SELECT s.*
            FROM risk_score_snapshot s
            WHERE s.trade_date = COALESCE(
                #{tradeDate}, (SELECT MAX(latest.trade_date) FROM risk_score_snapshot latest)
            )
              AND NOT EXISTS (
                  SELECT 1 FROM risk_score_snapshot newer
                  WHERE newer.object_type = s.object_type
                    AND newer.object_id = s.object_id
                    AND newer.horizon = s.horizon
                    AND newer.trade_date = s.trade_date
                    AND (newer.calculated_at &gt; s.calculated_at
                      OR (newer.calculated_at = s.calculated_at AND newer.id &gt; s.id))
              )
            ORDER BY s.object_type, s.object_id, s.horizon
            </script>
            """)
    List<RiskScoreSnapshotEntity> selectForOverview(@Param("tradeDate") LocalDate tradeDate);

    @Select("""
            <script>
            SELECT s.*
            FROM risk_score_snapshot s
            WHERE (#{objectType} IS NULL OR s.object_type = #{objectType})
              AND s.trade_date = (
                  SELECT MAX(latest.trade_date)
                  FROM risk_score_snapshot latest
                  WHERE latest.object_type = s.object_type AND latest.object_id = s.object_id
              )
              AND NOT EXISTS (
                  SELECT 1 FROM risk_score_snapshot newer
                  WHERE newer.object_type = s.object_type
                    AND newer.object_id = s.object_id
                    AND newer.horizon = s.horizon
                    AND newer.trade_date = s.trade_date
                    AND (newer.calculated_at &gt; s.calculated_at
                      OR (newer.calculated_at = s.calculated_at AND newer.id &gt; s.id))
              )
            ORDER BY s.object_type, s.object_id, s.horizon
            </script>
            """)
    List<RiskScoreSnapshotEntity> selectLatestObjects(@Param("objectType") String objectType);

    @Select("""
            <script>
            SELECT s.*
            FROM risk_score_snapshot s
            WHERE s.object_type = #{objectType}
              AND s.object_id = #{objectId}
              AND s.trade_date = (
                  SELECT MAX(latest.trade_date)
                  FROM risk_score_snapshot latest
                  WHERE latest.object_type = #{objectType} AND latest.object_id = #{objectId}
              )
              AND NOT EXISTS (
                  SELECT 1 FROM risk_score_snapshot newer
                  WHERE newer.object_type = s.object_type
                    AND newer.object_id = s.object_id
                    AND newer.horizon = s.horizon
                    AND newer.trade_date = s.trade_date
                    AND (newer.calculated_at &gt; s.calculated_at
                      OR (newer.calculated_at = s.calculated_at AND newer.id &gt; s.id))
              )
            ORDER BY s.horizon
            </script>
            """)
    List<RiskScoreSnapshotEntity> selectLatestForObject(
            @Param("objectType") String objectType,
            @Param("objectId") String objectId
    );

    @Select("""
            <script>
            SELECT s.*
            FROM risk_score_snapshot s
            WHERE s.object_type = #{objectType}
              AND s.object_id = #{objectId}
              <if test="startDate != null">AND s.trade_date &gt;= #{startDate}</if>
              <if test="endDate != null">AND s.trade_date &lt;= #{endDate}</if>
              AND NOT EXISTS (
                  SELECT 1 FROM risk_score_snapshot newer
                  WHERE newer.object_type = s.object_type
                    AND newer.object_id = s.object_id
                    AND newer.horizon = s.horizon
                    AND newer.trade_date = s.trade_date
                    AND (newer.calculated_at &gt; s.calculated_at
                      OR (newer.calculated_at = s.calculated_at AND newer.id &gt; s.id))
              )
            ORDER BY s.trade_date, s.horizon
            </script>
            """)
    List<RiskScoreSnapshotEntity> selectTrend(
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}
