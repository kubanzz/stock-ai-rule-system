package com.jx.tracker.risk.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.backfill.RiskBackfillSourceProbe;
import com.jx.tracker.risk.backfill.SourceProbeResult;
import com.jx.tracker.risk.backfill.SourceProbeSummary;
import com.jx.tracker.risk.backfill.TushareRiskBackfillSourceProbe;
import com.jx.tracker.risk.data.tushare.TushareRiskHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TuShare 真实权限与响应契约冒烟。默认跳过，且不读取本地 YAML。
 */
class TushareRiskContractSmokeTest {

    @Test
    @EnabledIf("integrationEnabled")
    void probesEveryRiskApiWithoutPrintingRawRowsOrCredentials() {
        String token = requiredSetting("TUSHARE_TOKEN");
        String apiUrl = setting("TUSHARE_API_URL", "https://api.tushare.pro");
        LocalDate endDate = LocalDate.parse(setting(
                "RISK_TUSHARE_SMOKE_END_DATE", LocalDate.now().minusDays(1).toString()));
        TushareRiskHttpClient client = new TushareRiskHttpClient(
                token, apiUrl, RestClient.builder(),
                new ObjectMapper().findAndRegisterModules());
        TushareRiskBackfillSourceProbe probe = new TushareRiskBackfillSourceProbe(
                client, new UnusedFallbackProbe());

        SourceProbeResult result = probe.probeTushare(endDate);

        result.summaries().forEach(TushareRiskContractSmokeTest::printSafeSummary);
        assertThat(result.reachable()).as(result.detail()).isTrue();
        assertThat(result.summaries()).hasSize(19);
    }

    static boolean integrationEnabled() {
        return "true".equalsIgnoreCase(setting("RISK_TUSHARE_IT", "false"))
                && text(setting("TUSHARE_TOKEN", null));
    }

    private static void printSafeSummary(SourceProbeSummary summary) {
        System.out.printf(
                "tushare api=%s rows=%d earliest=%s latest=%s quality=%s%n",
                summary.api(), summary.rowCount(),
                summary.earliestDate(), summary.latestDate(), summary.quality());
    }

    private static String requiredSetting(String name) {
        String value = setting(name, null);
        if (!text(value)) {
            throw new IllegalStateException(name + " is required");
        }
        return value.trim();
    }

    private static String setting(String name, String fallback) {
        String systemValue = System.getProperty(name);
        if (text(systemValue)) {
            return systemValue.trim();
        }
        String environmentValue = System.getenv(name);
        return text(environmentValue) ? environmentValue.trim() : fallback;
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static final class UnusedFallbackProbe implements RiskBackfillSourceProbe {

        @Override
        public SourceProbeResult probeAkTools(LocalDate endDate) {
            return SourceProbeResult.notProbed("aktools");
        }

        @Override
        public SourceProbeResult probeDerivedGateway(LocalDate endDate) {
            return SourceProbeResult.notProbed("derived-gateway");
        }
    }
}
