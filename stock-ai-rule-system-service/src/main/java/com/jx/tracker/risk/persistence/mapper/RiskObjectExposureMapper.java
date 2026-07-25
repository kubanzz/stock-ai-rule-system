package com.jx.tracker.risk.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jx.tracker.risk.persistence.entity.RiskObjectExposureEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface RiskObjectExposureMapper extends BaseMapper<RiskObjectExposureEntity> {

    @Select("""
            SELECT * FROM risk_object_exposure
            WHERE object_type = #{objectType}
              AND object_id = #{objectId}
              AND valid_from <= #{tradeDate}
              AND (valid_to IS NULL OR valid_to >= #{tradeDate})
              AND available_at <= #{asOf}
              AND quality_status IN ('available', 'valid_zero')
            ORDER BY exposure_weight DESC, parent_object_type, parent_object_id
            """)
    List<RiskObjectExposureEntity> selectActiveParents(
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("asOf") LocalDateTime asOf
    );

    /**
     * 当前发布口径只用于暂定评估展示，不得用于历史正式评分。
     */
    @Select("""
            SELECT exposure.*
            FROM risk_object_exposure exposure
            WHERE exposure.object_type = #{objectType}
              AND exposure.object_id = #{objectId}
              AND exposure.valid_from <= #{tradeDate}
              AND (exposure.valid_to IS NULL OR exposure.valid_to >= #{tradeDate})
              AND exposure.quality_status IN ('available', 'valid_zero')
              AND NOT EXISTS (
                  SELECT 1
                  FROM risk_object_exposure newer
                  WHERE newer.object_type = exposure.object_type
                    AND newer.object_id = exposure.object_id
                    AND newer.parent_object_type = exposure.parent_object_type
                    AND newer.valid_from <= #{tradeDate}
                    AND (newer.valid_to IS NULL OR newer.valid_to >= #{tradeDate})
                    AND newer.quality_status IN ('available', 'valid_zero')
                    AND (newer.available_at > exposure.available_at
                      OR (newer.available_at = exposure.available_at
                        AND newer.id > exposure.id))
              )
            ORDER BY exposure.exposure_weight DESC,
                     exposure.parent_object_type, exposure.parent_object_id
            """)
    List<RiskObjectExposureEntity> selectLatestPublishedParents(
            @Param("objectType") String objectType,
            @Param("objectId") String objectId,
            @Param("tradeDate") LocalDate tradeDate
    );
}
