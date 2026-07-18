package com.jx.tracker.risk.data.market;

import com.jx.tracker.market.data.util.SymbolNormalizer;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public final class AshareRiskObjectCatalog {

    public static final String CN_A = "CN-A";
    private static final Pattern SW1_CODE = Pattern.compile("^\\d{6}$");
    private static final Pattern A_SHARE = Pattern.compile("^\\d{6}\\.(SZ|SH|BJ)$");

    public RiskObjectKey market() {
        return new RiskObjectKey(RiskObjectType.MARKET, CN_A);
    }

    public RiskObjectKey sector(String sw1Code) {
        if (sw1Code == null || !SW1_CODE.matcher(sw1Code).matches()) {
            throw new IllegalArgumentException("申万一级行业代码必须为六位数字");
        }
        return new RiskObjectKey(RiskObjectType.SECTOR, "SW1:" + sw1Code);
    }

    public RiskObjectKey stock(String rawSymbol) {
        String normalized = SymbolNormalizer.normalize(rawSymbol);
        if (normalized == null || !A_SHARE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("风险对象必须是规范 A 股代码");
        }
        return new RiskObjectKey(RiskObjectType.STOCK, normalized);
    }

    public Optional<IndustryExposure> effectiveIndustry(
            RiskObjectKey stock,
            LocalDate tradeDate,
            LocalDateTime evaluationAt,
            List<IndustryExposure> exposures
    ) {
        if (stock == null || stock.objectType() != RiskObjectType.STOCK
                || tradeDate == null || evaluationAt == null) {
            throw new IllegalArgumentException("stock, tradeDate and evaluationAt are required");
        }
        return (exposures == null ? List.<IndustryExposure>of() : exposures).stream()
                .filter(exposure -> exposure.stock().equals(stock))
                .filter(exposure -> exposure.qualityStatus() == RiskDataQualityStatus.AVAILABLE)
                .filter(exposure -> exposure.isEffectiveOn(tradeDate))
                .filter(exposure -> !exposure.availableAt().isAfter(evaluationAt))
                .max(Comparator.comparing(IndustryExposure::validFrom)
                        .thenComparing(IndustryExposure::availableAt));
    }
}
