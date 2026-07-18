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
}
