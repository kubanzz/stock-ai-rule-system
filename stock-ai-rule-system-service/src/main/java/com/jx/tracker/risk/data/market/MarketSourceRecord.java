package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;

import java.time.LocalDate;
import java.time.LocalDateTime;

public interface MarketSourceRecord {

    RiskObjectKey object();

    LocalDate tradeDate();

    LocalDateTime observedAt();

    LocalDateTime availableAt();

    String source();

    RiskDataQualityStatus qualityStatus();
}
