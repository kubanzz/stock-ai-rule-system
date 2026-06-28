package com.jx.tracker.signal.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;
import com.jx.tracker.domain.dto.SignalGenerateRequestDto;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.vo.StockAnalysisVo;
import com.jx.tracker.domain.vo.StockSignalItemVo;
import com.jx.tracker.signal.service.StockSignalService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "股票信号")
public class StockSignalController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StockSignalService stockSignalService;

    public StockSignalController(StockSignalService stockSignalService) {
        this.stockSignalService = stockSignalService;
    }

    @GetMapping("/api/signals")
    public PageResult<StockSignalItemVo> list(@RequestParam(required = false) LocalDate date,
                                              @RequestParam(required = false) String signal,
                                              @RequestParam(required = false) String symbol) {
        List<StockSignalItemVo> rows = stockSignalService.listSignals(date, signal, symbol)
                .stream()
                .map(this::toItemVo)
                .toList();
        return PageResult.getDataTable(rows, (long) rows.size());
    }

    @PostMapping("/api/signals/generate")
    public AjaxResult generate(@RequestBody SignalGenerateRequestDto dto) {
        StockSignalDaily signal = stockSignalService.generateDailySignal(
                dto.getSymbol(),
                dto.getSignalDate(),
                dto.getFactors()
        );
        return AjaxResult.success(signal);
    }

    @PostMapping("/api/signals/generate-from-factors")
    public AjaxResult generateFromFactors(@RequestBody DailyWorkflowTriggerDto dto) {
        return AjaxResult.success(stockSignalService.generateDailySignalsFromFactors(
                dto.getTradeDate(),
                dto.getSymbols()
        ));
    }

    @GetMapping("/api/stocks/{symbol}/analysis")
    public AjaxResult analysis(@PathVariable String symbol, @RequestParam LocalDate date) {
        StockSignalDaily signal = stockSignalService.getSignal(symbol, date);
        if (signal == null) {
            return AjaxResult.success(StockAnalysisVo.builder()
                    .symbol(symbol)
                    .factors(Map.of())
                    .triggeredRules(List.of())
                    .explanation("")
                    .build());
        }
        return AjaxResult.success(StockAnalysisVo.builder()
                .symbol(signal.getSymbol())
                .signal(signal.getSignal())
                .factors(Map.of())
                .triggeredRules(readTriggeredRules(signal.getTriggeredRules()))
                .explanation(signal.getExplanation())
                .riskDisclaimer(riskDisclaimer(signal.getRiskDisclaimer()))
                .build());
    }

    private StockSignalItemVo toItemVo(StockSignalDaily signal) {
        return StockSignalItemVo.builder()
                .symbol(signal.getSymbol())
                .signal(signal.getSignal())
                .signalLevel(signal.getSignalLevel())
                .bullishScore(signal.getBullishScore())
                .bearishScore(signal.getBearishScore())
                .riskScore(signal.getRiskScore())
                .confidence(signal.getConfidence())
                .triggeredRuleCount(readTriggeredRules(signal.getTriggeredRules()).size())
                .riskDisclaimer(riskDisclaimer(signal.getRiskDisclaimer()))
                .build();
    }

    private String riskDisclaimer(String value) {
        return value == null || value.isBlank() ? StockRiskConstants.SIGNAL_RISK_DISCLAIMER : value;
    }

    private List<String> readTriggeredRules(String triggeredRules) {
        if (triggeredRules == null || triggeredRules.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(triggeredRules, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of(triggeredRules);
        }
    }
}
