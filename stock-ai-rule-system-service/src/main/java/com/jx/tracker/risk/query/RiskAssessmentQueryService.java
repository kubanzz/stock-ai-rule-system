package com.jx.tracker.risk.query;

import com.jx.tracker.common.PageResult;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectDetail;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectListItem;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskOverview;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskTrendPoint;

import java.time.LocalDate;
import java.util.List;

public interface RiskAssessmentQueryService {

    RiskOverview overview(String horizon, LocalDate tradeDate);

    PageResult<RiskObjectListItem> listObjects(
            String objectType,
            String level,
            String horizon,
            LocalDate tradeDate,
            String keyword,
            String parentObjectType,
            String parentObjectId,
            int pageNum,
            int pageSize
    );

    RiskObjectDetail objectDetail(
            String objectType,
            String objectId,
            String horizon,
            LocalDate tradeDate
    );

    List<RiskTrendPoint> trend(
            String objectType,
            String objectId,
            String horizon,
            LocalDate startDate,
            LocalDate endDate
    );
}
