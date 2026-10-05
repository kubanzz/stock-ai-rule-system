package com.jx.tracker.signal.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;
import com.jx.tracker.domain.dto.SignalGenerateRequestDto;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.vo.StockAnalysisVo;
import com.jx.tracker.domain.vo.StockSignalItemVo;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import com.jx.tracker.signal.service.StockSignalService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
    private final StockFactorDailyMapper stockFactorDailyMapper;

    public StockSignalController(StockSignalService stockSignalService) {
        this(stockSignalService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StockSignalController(StockSignalService stockSignalService, StockFactorDailyMapper stockFactorDailyMapper) {
        this.stockSignalService = stockSignalService;
        this.stockFactorDailyMapper = stockFactorDailyMapper;
    }

    @GetMapping("/api/signals")
    public PageResult<StockSignalItemVo> list(@RequestParam(value = "date", required = false) LocalDate date,
                                              @RequestParam(value = "signal", required = false) String signal,
                                              @RequestParam(value = "symbol", required = false) String symbol,
                                              @RequestParam(value = "strategyCode", required = false) String strategyCode,
                                              @RequestParam(value = "strategyVersion", required = false) String strategyVersion) {
        List<StockSignalItemVo> rows = stockSignalService.listSignals(date, signal, symbol, strategyCode, strategyVersion)
                .stream()
                .map(this::toItemVo)
                .toList();
        return PageResult.getDataTable(rows, (long) rows.size());
    }

    @PostMapping("/api/signals/generate")
    public AjaxResult generate(@RequestBody SignalGenerateRequestDto dto) {
        return AjaxResult.success(stockSignalService.generateDailySignals(
                dto.getSymbol(), dto.getSignalDate(), dto.getFactors(), dto.getStrategyCode()));
    }

    @PostMapping("/api/signals/generate-from-factors")
    public AjaxResult generateFromFactors(@RequestBody DailyWorkflowTriggerDto dto) {
        return AjaxResult.success(stockSignalService.generateDailySignalsFromFactors(
                dto.getTradeDate(),
                dto.getSymbols(), dto.getStrategyCode()
        ));
    }

    @GetMapping("/api/stocks/{symbol}/analysis")
    public AjaxResult analysis(@PathVariable("symbol") String symbol,
                               @RequestParam(value = "date", required = false) LocalDate date,
                               @RequestParam(value = "signalId", required = false) Long signalId,
                               @RequestParam(value = "strategyCode", required = false) String strategyCode,
                               @RequestParam(value = "strategyVersion", required = false) String strategyVersion) {
        StockSignalDaily signal = stockSignalService.getSignal(symbol, date, signalId, strategyCode, strategyVersion);
        Map<String, Object> factors = readFactors(symbol, signal, date);
        if (signal == null) {
            return AjaxResult.success(StockAnalysisVo.builder()
                    .symbol(symbol)
                    .factors(factors)
                    .triggeredRules(List.of())
                    .explanation("")
                    .build());
        }
        return AjaxResult.success(StockAnalysisVo.builder()
                .signalId(signal.getId())
                .strategyCode(signal.getStrategyCode())
                .strategyVersion(signal.getStrategyVersion())
                .strategyName(signal.getStrategyName())
                .symbol(signal.getSymbol())
                .signal(signal.getSignal())
                .factors(factors)
                .triggeredRules(readTriggeredRules(signal.getTriggeredRules()))
                .explanation(signal.getExplanation())
                .riskDisclaimer(riskDisclaimer(signal.getRiskDisclaimer()))
                .build());
    }

    private StockSignalItemVo toItemVo(StockSignalDaily signal) {
        return StockSignalItemVo.builder()
                .signalId(signal.getId())
                .strategyCode(signal.getStrategyCode())
                .strategyVersion(signal.getStrategyVersion())
                .strategyName(signal.getStrategyName())
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

    private Map<String, Object> readFactors(String symbol, StockSignalDaily signal, LocalDate date) {
        if (stockFactorDailyMapper == null) {
            return Map.of();
        }
        LocalDate factorDate = signal == null ? date : signal.getSignalDate();
        StockFactorDaily factor = stockFactorDailyMapper.selectOne(new LambdaQueryWrapper<StockFactorDaily>()
                .eq(StockFactorDaily::getSymbol, symbol)
                .eq(factorDate != null, StockFactorDaily::getTradeDate, factorDate)
                .orderByDesc(StockFactorDaily::getTradeDate)
                .last("LIMIT 1"));
        if (factor == null || factor.getFactorJson() == null || factor.getFactorJson().isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(factor.getFactorJson(), new TypeReference<Map<String, Object>>() { });
        } catch (Exception ignored) {
            return Map.of("raw", factor.getFactorJson());
        }
    }
}
