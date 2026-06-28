package com.jx.tracker.scheduler;

import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.vo.DailyWorkflowStepResultVo;
import com.jx.tracker.domain.vo.StockFactorDailyVo;
import com.jx.tracker.service.IStockFactorDailyService;
import com.jx.tracker.signal.service.StockSignalService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyWorkflowStepHandlersTest {

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

    private DailyWorkflowContext context(LocalDate tradeDate, List<String> symbols) {
        DailyWorkflowTriggerDto request = new DailyWorkflowTriggerDto();
        request.setTradeDate(tradeDate);
        request.setSymbols(symbols);
        request.setDryRun(false);
        return new DailyWorkflowContext("test-run", request, WorkflowTriggerType.MANUAL);
    }
}
