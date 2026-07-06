package com.jx.tracker.scheduler;

import com.jx.tracker.ai.review.AiReviewService;
import com.jx.tracker.backtest.BacktestService;
import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;
import com.jx.tracker.domain.dto.CandidateRuleDto;
import com.jx.tracker.domain.entity.BacktestResult;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.dto.AiReviewResponseDto;
import com.jx.tracker.domain.vo.DailyWorkflowStepResultVo;
import com.jx.tracker.domain.vo.StockFactorDailyVo;
import com.jx.tracker.market.data.dto.MarketDataSyncResultDto;
import com.jx.tracker.market.data.service.MarketDataSyncService;
import com.jx.tracker.service.IStockFactorDailyService;
import com.jx.tracker.signal.service.StockSignalService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyWorkflowStepHandlersTest {

    @Test
    void marketDataCollectionHandlerRunsConfiguredSyncServices() {
        MarketDataSyncService syncService = mock(MarketDataSyncService.class);
        MarketDataSyncResultDto syncResult = new MarketDataSyncResultDto();
        syncResult.setStatus("success");
        syncResult.setInserted(1);
        when(syncService.syncStockList(any())).thenReturn(syncResult);
        when(syncService.syncTradeCalendar(any())).thenReturn(syncResult);
        when(syncService.syncDailyQuotes(any())).thenReturn(syncResult);

        DailyWorkflowStepResultVo result = new MarketDataCollectionStepHandler(syncService)
                .execute(context(LocalDate.of(2026, 6, 26), List.of("000001.SZ", "000002.SZ")));

        assertThat(result.getStepCode()).isEqualTo(WorkflowStepCode.MARKET_DATA_COLLECTION.getCode());
        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getDetails()).containsEntry("stockListStatus", "success");
        assertThat(result.getDetails()).containsEntry("tradeCalendarStatus", "success");
        assertThat(result.getDetails()).containsEntry("dailyQuoteSyncCount", 2);
        verify(syncService).syncStockList(argThat(request ->
                LocalDate.of(2026, 6, 26).equals(request.getStartDate())
                        && "manual".equals(request.getTriggerType())));
        verify(syncService).syncTradeCalendar(argThat(request ->
                LocalDate.of(2026, 6, 26).equals(request.getStartDate())
                        && LocalDate.of(2026, 6, 26).equals(request.getEndDate())));
        verify(syncService, times(2)).syncDailyQuotes(any());
    }

    @Test
    void factorCalculationHandlerRunsBatchCalculationForRequestedSymbols() {
        IStockFactorDailyService factorService = mock(IStockFactorDailyService.class);
        LocalDate tradeDate = LocalDate.of(2026, 6, 26);
        when(factorService.calculateAndSaveBatch(List.of("000001.SZ", "000002.SZ"), tradeDate))
                .thenReturn(List.of(
                        StockFactorDailyVo.builder().symbol("000001.SZ").tradeDate(tradeDate).factors(Map.of()).build(),
                        StockFactorDailyVo.builder().symbol("000002.SZ").tradeDate(tradeDate).factors(Map.of()).build()
                ));

        DailyWorkflowStepResultVo result = new FactorCalculationStepHandler(factorService)
                .execute(context(tradeDate, List.of("000001.SZ", "000002.SZ")));

        assertThat(result.getStepCode()).isEqualTo(WorkflowStepCode.FACTOR_CALCULATION.getCode());
        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getDetails()).containsEntry("factorCount", 2);
        verify(factorService).calculateAndSaveBatch(List.of("000001.SZ", "000002.SZ"), tradeDate);
    }

    @Test
    void signalGenerationHandlerUsesPersistedFactorsForRequestedTradeDate() {
        StockSignalService signalService = mock(StockSignalService.class);
        LocalDate tradeDate = LocalDate.of(2026, 6, 26);
        when(signalService.generateDailySignalsFromFactors(tradeDate, List.of("000001.SZ")))
                .thenReturn(List.of(StockSignalDaily.builder().symbol("000001.SZ").build()));

        DailyWorkflowStepResultVo result = new SignalGenerationStepHandler(signalService)
                .execute(context(tradeDate, List.of("000001.SZ")));

        assertThat(result.getStepCode()).isEqualTo(WorkflowStepCode.SIGNAL_GENERATION.getCode());
        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getDetails()).containsEntry("signalCount", 1);
        verify(signalService).generateDailySignalsFromFactors(tradeDate, List.of("000001.SZ"));
    }

    @Test
    void aiReviewHandlerRunsReviewAndStoresCandidateCodesForBacktest() {
        AiReviewService aiReviewService = mock(AiReviewService.class);
        AiReviewResponseDto response = new AiReviewResponseDto();
        response.setDiagnosis("复盘完成");
        response.setCandidateRules(List.of(CandidateRuleDto.builder()
                .candidateCode("CR_20260626_0001")
                .build()));
        when(aiReviewService.review(any())).thenReturn(response);
        DailyWorkflowContext context = context(LocalDate.of(2026, 6, 26), List.of("000001.SZ"));

        DailyWorkflowStepResultVo result = new AiReviewWorkflowStepHandler(aiReviewService).execute(context);

        assertThat(result.getStepCode()).isEqualTo(WorkflowStepCode.AI_REVIEW.getCode());
        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getDetails()).containsEntry("reviewCount", 1);
        assertThat(result.getDetails()).containsEntry("candidateRuleCount", 1);
        assertThat(context.getAttribute("candidateRuleCodes", List.class)).containsExactly("CR_20260626_0001");
        verify(aiReviewService).review(argThat(request ->
                LocalDate.of(2026, 6, 26).equals(request.getDate())
                        && "000001.SZ".equals(request.getSymbol())));
    }

    @Test
    void candidateRuleBacktestHandlerRunsBacktestForAiGeneratedCandidates() {
        BacktestService backtestService = mock(BacktestService.class);
        when(backtestService.runCandidateRuleBacktest(any())).thenReturn(BacktestResult.builder()
                .id(10L)
                .objectCode("CR_20260626_0001")
                .status("success")
                .build());
        DailyWorkflowContext context = context(LocalDate.of(2026, 6, 26), List.of("000001.SZ"));
        context.putAttribute("candidateRuleCodes", List.of("CR_20260626_0001"));

        DailyWorkflowStepResultVo result = new CandidateRuleBacktestStepHandler(backtestService).execute(context);

        assertThat(result.getStepCode()).isEqualTo(WorkflowStepCode.CANDIDATE_RULE_BACKTEST.getCode());
        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getDetails()).containsEntry("backtestCount", 1);
        assertThat(result.getDetails()).containsEntry("candidateRuleCodes", List.of("CR_20260626_0001"));
        verify(backtestService).runCandidateRuleBacktest(argThat(request ->
                "candidate_rule".equals(request.getObjectType())
                        && "CR_20260626_0001".equals(request.getObjectCode())
                        && LocalDate.of(2026, 6, 26).equals(request.getEndDate())));
    }

    @Test
    void candidateRuleBacktestHandlerSkipsWhenAiReviewProducesNoCandidates() {
        BacktestService backtestService = mock(BacktestService.class);
        DailyWorkflowStepResultVo result = new CandidateRuleBacktestStepHandler(backtestService)
                .execute(context(LocalDate.of(2026, 6, 26), List.of("000001.SZ")));

        assertThat(result.getStatus()).isEqualTo("skipped");
        assertThat(result.getDetails()).containsEntry("optional", true);
    }

    private DailyWorkflowContext context(LocalDate tradeDate, List<String> symbols) {
        DailyWorkflowTriggerDto request = new DailyWorkflowTriggerDto();
        request.setTradeDate(tradeDate);
        request.setSymbols(symbols);
        request.setDryRun(false);
        return new DailyWorkflowContext("test-run", request, WorkflowTriggerType.MANUAL);
    }
}
