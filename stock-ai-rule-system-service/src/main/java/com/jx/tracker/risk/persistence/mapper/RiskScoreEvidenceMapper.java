package com.jx.tracker.risk.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jx.tracker.risk.persistence.entity.RiskScoreEvidenceEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface RiskScoreEvidenceMapper extends BaseMapper<RiskScoreEvidenceEntity> {

    @Select("""
            <script>
            SELECT * FROM risk_score_evidence
            WHERE snapshot_id IN
            <foreach collection="snapshotIds" item="snapshotId" open="(" separator="," close=")">
                #{snapshotId}
            </foreach>
            ORDER BY snapshot_id, dimension_code, weighted_contribution DESC, indicator_code
            </script>
            """)
    List<RiskScoreEvidenceEntity> selectBySnapshotIds(@Param("snapshotIds") List<Long> snapshotIds);
}
