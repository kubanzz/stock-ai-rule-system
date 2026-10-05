package com.jx.tracker.verification;

import com.jx.tracker.domain.entity.BacktestResult;
import com.jx.tracker.mapper.BacktestResultMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResearchVerificationServiceTest {

    @Test
    void genericDescriptiveReportCannotBecomeVerified() {
        BacktestResultMapper mapper = mock(BacktestResultMapper.class);
        BacktestResult report = BacktestResult.builder()
                .id(7L).objectType("strategy").objectCode("S1")
                .holdingPeriod(1).status("success").triggerCount(120)
                .resultJson("""
                        {"validation":{"evaluatedSamples":120,"overallWinRate":0.70,
                         "periodSamplesSufficient":true,"stableAcrossPeriods":true,
                         "descriptiveQualified":true}}
                        """)
                .build();
        when(mapper.selectList(any())).thenReturn(List.of(report));

        var summary = new ResearchVerificationService(mapper).summarize("strategy", "S1", 1);

        assertThat(summary.researchStatus()).isEqualTo("exploratory");
        assertThat(summary.independentFinalTest()).isFalse();
        assertThat(summary.statusReason()).contains("描述性");
    }

    @Test
    void selfDeclaredVerificationAndPeriodFlagsCannotAuthorizeProduction() {
        BacktestResultMapper mapper = mock(BacktestResultMapper.class);
        String json = """
                {"researchStatus":"verified","validation":{
                  "evaluatedSamples":120,"overallWinRate":0.70,"entryDates":40,"symbols":25,
                  "baselineLift":0.04,"baselineCompared":true,"nonOverlapping":true,"independentFinalTest":true,
                  "completeTriggerRecord":true,"periodSamplesSufficient":true,
                  "stableAcrossPeriods":true}}
                """;
        BacktestResult report = BacktestResult.builder().id(8L).status("success")
                .triggerCount(120).resultJson(json).build();
        when(mapper.selectList(any())).thenReturn(List.of(report));

        var service = new ResearchVerificationService(mapper);
        var summary = service.summarize("strategy", "S1", 1);

        assertThat(summary.researchStatus()).isEqualTo("exploratory");
        assertThat(service.isVerifiedStrategy("S1")).isFalse();
        assertThat(summary.statusReason()).contains("尚未经独立验收", "不能晋级候选/已验证");
        assertThat(summary.winRate()).isEqualByComparingTo(new BigDecimal("0.70"));
        assertThat(summary.periodsSufficient()).isTrue();
        assertThat(summary.periodsStable()).isTrue();
        assertThat(summary.independentFinalTest()).isTrue();
    }

    @Test
    void claimedCandidateThresholdsRemainDescriptiveWithoutIndependentEvidence() {
        var service = new ResearchVerificationService(mock(BacktestResultMapper.class));
        BacktestResult report = BacktestResult.builder().status("success").resultJson("""
                {"validation":{"evaluatedSamples":1000,"overallWinRate":0.99,
                 "entryDates":200,"symbols":100,"baselineLift":0.20,
                 "baselineCompared":true,"nonOverlapping":true,
                 "periodSamplesSufficient":true,"stableAcrossPeriods":true}}
                """).build();

        var summary = service.summarize(report);

        assertThat(summary.researchStatus()).isEqualTo("exploratory");
        assertThat(summary.evaluatedCount()).isEqualTo(1000);
        assertThat(summary.baselineLift()).isEqualByComparingTo("0.20");
        assertThat(service.isVerifiedStrategy("S1")).isFalse();
    }

    @Test
    void convertsPercentagePointLiftToRatioWithoutChangingRatioFields() {
        var service = new ResearchVerificationService(mock(BacktestResultMapper.class));
        var percentagePointReport = BacktestResult.builder().status("success")
                .resultJson("{\"baselineLiftPercentagePoints\":4.25}").build();
        var ratioReport = BacktestResult.builder().status("success")
                .resultJson("{\"baselineLift\":0.03,\"baselineLiftPercentagePoints\":4.25}").build();

        assertThat(service.summarize(percentagePointReport).baselineLift()).isEqualByComparingTo("0.0425");
        assertThat(service.summarize(ratioReport).baselineLift()).isEqualByComparingTo("0.03");
    }

    @Test
    void missingAndFailedReportsDoNotBecomeResearchEvidence() {
        var service = new ResearchVerificationService(mock(BacktestResultMapper.class));

        assertThat(service.summarize((BacktestResult) null).researchStatus()).isEqualTo("pending");
        assertThat(service.summarize(BacktestResult.builder().status("failed")
                .resultJson("{\"researchStatus\":\"verified\"}").build()).researchStatus()).isEqualTo("rejected");
        assertThat(service.isVerifiedStrategy(null)).isFalse();
    }
}
