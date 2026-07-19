package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AshareRiskObjectCatalogTest {

    private final AshareRiskObjectCatalog catalog = new AshareRiskObjectCatalog();

    @Test
    void createsOnlyCanonicalAShareObjects() {
        assertThat(catalog.market()).isEqualTo(new RiskObjectKey(RiskObjectType.MARKET, "CN-A"));
        assertThat(catalog.sector("801010"))
                .isEqualTo(new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801010"));
        assertThat(catalog.stock("sz000001"))
                .isEqualTo(new RiskObjectKey(RiskObjectType.STOCK, "000001.SZ"));
        assertThat(catalog.stock("920992"))
                .isEqualTo(new RiskObjectKey(RiskObjectType.STOCK, "920992.BJ"));

        assertThatThrownBy(() -> catalog.stock("AAPL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A 股");
        assertThatThrownBy(() -> catalog.stock("bad-code"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.sector("SW1:801010"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("申万一级");
    }

    @Test
    void selectsMembershipEffectiveOnTradeDateWithoutFutureAvailability() {
        RiskObjectKey stock = catalog.stock("000001.SZ");
        IndustryExposure oldExposure = exposure(stock, "801010", LocalDate.of(2020, 1, 1),
                LocalDate.of(2024, 12, 31), LocalDateTime.of(2020, 1, 2, 8, 0));
        IndustryExposure current = exposure(stock, "801780", LocalDate.of(2025, 1, 1),
                null, LocalDateTime.of(2025, 1, 2, 8, 0));
        IndustryExposure futureKnown = exposure(stock, "801790", LocalDate.of(2025, 1, 1),
                null, LocalDateTime.of(2026, 7, 19, 8, 0));

        assertThat(catalog.effectiveIndustry(
                stock,
                LocalDate.of(2026, 7, 18),
                LocalDateTime.of(2026, 7, 18, 18, 0),
                List.of(oldExposure, current, futureKnown)
        )).contains(current);
        assertThat(catalog.effectiveIndustry(
                stock,
                LocalDate.of(2024, 6, 1),
                LocalDateTime.of(2024, 6, 1, 18, 0),
                List.of(oldExposure, current)
        )).contains(oldExposure);
    }

    private IndustryExposure exposure(
            RiskObjectKey stock,
            String sectorCode,
            LocalDate validFrom,
            LocalDate validTo,
            LocalDateTime availableAt
    ) {
        return new IndustryExposure(
                stock,
                catalog.sector(sectorCode),
                validFrom,
                validTo,
                availableAt.minusHours(1),
                availableAt,
                "aktools",
                RiskDataQualityStatus.AVAILABLE
        );
    }
}
