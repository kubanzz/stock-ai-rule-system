package com.jx.tracker.risk.query;

import com.jx.tracker.common.PageResult;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.Overview;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectDetail;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectSummary;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskTrend;

import java.time.LocalDate;

public interface RiskAssessmentQueryService {

    Overview overview(LocalDate tradeDate);

    PageResult<RiskObjectSummary> listObjects(
            String objectType,
            String riskLevel,
            int pageNum,
            int pageSize
    );

    RiskObjectDetail objectDetail(String objectType, String objectId);

    RiskTrend trend(String objectType, String objectId, LocalDate startDate, LocalDate endDate);
}
