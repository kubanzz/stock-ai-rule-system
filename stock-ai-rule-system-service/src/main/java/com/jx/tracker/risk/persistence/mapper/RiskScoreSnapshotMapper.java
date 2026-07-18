package com.jx.tracker.risk.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jx.tracker.risk.persistence.entity.RiskScoreSnapshotEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface RiskScoreSnapshotMapper extends BaseMapper<RiskScoreSnapshotEntity> {

    @Select("SELECT MAX(trade_date) FROM risk_score_snapshot WHERE horizon = #{horizon}")
    LocalDate selectLatestTradeDate(@Param("horizon") String horizon);

    @Select("""
            <script>
            SELECT MAX(trade_date)
            FROM risk_score_snapshot
            WHERE object_type = #{objectType} AND object_id = #{objectId}
            <if test="horizon != null">AND horizon = #{horizon}</if>
            </script>
            """)
    LocalDate selectLatestTradeDateForObject(
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("horizon") String horizon
    );

    @Select("""
            SELECT s.*, COALESCE(NULLIF(sb.name, ''), s.object_id) AS object_name
            FROM risk_score_snapshot s
            LEFT JOIN stock_base sb ON s.object_type = 'stock' AND sb.symbol = s.object_id
            WHERE s.horizon = #{horizon}
              AND s.trade_date = #{tradeDate}
              AND NOT EXISTS (
                  SELECT 1 FROM risk_score_snapshot newer
                  WHERE newer.object_type = s.object_type
                    AND newer.object_id = s.object_id
                    AND newer.horizon = s.horizon
                    AND newer.trade_date = s.trade_date
                    AND (newer.calculated_at > s.calculated_at
                      OR (newer.calculated_at = s.calculated_at AND newer.id > s.id))
              )
            ORDER BY s.object_type, s.object_id
            """)
    List<RiskScoreSnapshotEntity> selectForOverview(
            @Param("horizon") String horizon,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM risk_score_snapshot s
            LEFT JOIN stock_base sb ON s.object_type = 'stock' AND sb.symbol = s.object_id
            WHERE s.horizon = #{horizon}
              AND s.trade_date = #{tradeDate}
              <if test="objectType != null">AND s.object_type = #{objectType}</if>
              <if test="level != null">AND s.risk_level = #{level}</if>
              <if test="keyword != null">
                AND (s.object_id LIKE CONCAT('%', #{keyword}, '%')
                  OR COALESCE(sb.name, '') LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              AND NOT EXISTS (
                  SELECT 1 FROM risk_score_snapshot newer
                  WHERE newer.object_type = s.object_type
                    AND newer.object_id = s.object_id
                    AND newer.horizon = s.horizon
                    AND newer.trade_date = s.trade_date
                    AND (newer.calculated_at &gt; s.calculated_at
                      OR (newer.calculated_at = s.calculated_at AND newer.id &gt; s.id))
              )
            </script>
            """)
    long countObjectPage(
            @Param("objectType") String objectType,
            @Param("level") String level,
            @Param("horizon") String horizon,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("keyword") String keyword
    );

    @Select("""
            <script>
            SELECT s.*, COALESCE(NULLIF(sb.name, ''), s.object_id) AS object_name
            FROM risk_score_snapshot s
            LEFT JOIN stock_base sb ON s.object_type = 'stock' AND sb.symbol = s.object_id
            WHERE s.horizon = #{horizon}
              AND s.trade_date = #{tradeDate}
              <if test="objectType != null">AND s.object_type = #{objectType}</if>
              <if test="level != null">AND s.risk_level = #{level}</if>
              <if test="keyword != null">
                AND (s.object_id LIKE CONCAT('%', #{keyword}, '%')
                  OR COALESCE(sb.name, '') LIKE CONCAT('%', #{keyword}, '%'))
              </if>
              AND NOT EXISTS (
                  SELECT 1 FROM risk_score_snapshot newer
                  WHERE newer.object_type = s.object_type
                    AND newer.object_id = s.object_id
                    AND newer.horizon = s.horizon
                    AND newer.trade_date = s.trade_date
                    AND (newer.calculated_at &gt; s.calculated_at
                      OR (newer.calculated_at = s.calculated_at AND newer.id &gt; s.id))
              )
            ORDER BY CASE s.risk_level
                       WHEN 'critical' THEN 4 WHEN 'warning' THEN 3
                       WHEN 'watch' THEN 2 WHEN 'normal' THEN 1 ELSE 0 END DESC,
                     s.total_score DESC, s.object_type, s.object_id
            LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<RiskScoreSnapshotEntity> selectObjectPage(
            @Param("objectType") String objectType,
            @Param("level") String level,
            @Param("horizon") String horizon,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("keyword") String keyword,
            @Param("offset") long offset,
            @Param("pageSize") int pageSize
    );

    @Select("""
            <script>
            SELECT s.*, COALESCE(NULLIF(sb.name, ''), s.object_id) AS object_name
            FROM risk_score_snapshot s
            LEFT JOIN stock_base sb ON s.object_type = 'stock' AND sb.symbol = s.object_id
            WHERE s.object_type = #{objectType}
              AND s.object_id = #{objectId}
              AND s.trade_date = #{tradeDate}
              <if test="horizon != null">AND s.horizon = #{horizon}</if>
              AND NOT EXISTS (
                  SELECT 1 FROM risk_score_snapshot newer
                  WHERE newer.object_type = s.object_type
                    AND newer.object_id = s.object_id
                    AND newer.horizon = s.horizon
                    AND newer.trade_date = s.trade_date
                    AND (newer.calculated_at &gt; s.calculated_at
                      OR (newer.calculated_at = s.calculated_at AND newer.id &gt; s.id))
              )
            ORDER BY CASE s.horizon
                       WHEN '1-5d' THEN 1 WHEN '5-20d' THEN 2 ELSE 3 END
            </script>
            """)
    List<RiskScoreSnapshotEntity> selectForObject(
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("horizon") String horizon,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Select("""
            <script>
            SELECT s.*
            FROM risk_score_snapshot s
            WHERE s.object_type = #{objectType}
              AND s.object_id = #{objectId}
              AND s.horizon = #{horizon}
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
            ORDER BY s.trade_date
            </script>
            """)
    List<RiskScoreSnapshotEntity> selectTrend(
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("horizon") String horizon,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}
