package com.jx.tracker.ai.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.dto.AiReviewRequestDto;
import com.jx.tracker.domain.dto.AiReviewResponseDto;
import com.jx.tracker.domain.entity.AiReviewReport;
import com.jx.tracker.domain.entity.CandidateRule;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.AiReviewReportMapper;
import com.jx.tracker.mapper.CandidateRuleMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiReviewServiceImplTest {

    private final AiReviewReportMapper reportMapper = mock(AiReviewReportMapper.class);
    private final CandidateRuleMapper candidateRuleMapper = mock(CandidateRuleMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiReviewOutputParser parser = new AiReviewOutputParser(objectMapper);
    private final AiReviewPromptTemplate promptTemplate = new AiReviewPromptTemplate(objectMapper);

    @Test
    void savesReviewReportAndGeneratesCandidateRuleFromStructuredLlmOutput() {
        LlmClient llmClient = request -> new LlmResponse("mock-llm", """
                {
                  "diagnosis": "弱势市场中放量突破失效，原规则需要增加市场过滤。",
                  "related_rules": ["R_TREND_BREAKOUT_001"],
                  "suggestions": [
                    {
                      "type": "add_filter",
                      "rule_id": "R_TREND_BREAKOUT_001",
                      "condition": "market_status != weak AND industry_status != weak",
                      "reason": "减少弱势市场下的假突破误判",
                      "risk": "可能减少部分强势个股机会",
                      "need_backtest": true
                    }
                  ],
                  "need_backtest": true,
                  "risk": "候选规则必须回测和人工审核，仅作股票研究辅助决策。"
                }
                """);
        AiReviewService service = new AiReviewServiceImpl(reportMapper, candidateRuleMapper, llmClient, parser, promptTemplate, objectMapper);
        when(reportMapper.insert(any(AiReviewReport.class))).thenReturn(1);
        when(candidateRuleMapper.insert(any(CandidateRule.class))).thenReturn(1);

        AiReviewResponseDto response = service.review(reviewRequest());

        ArgumentCaptor<AiReviewReport> reportCaptor = ArgumentCaptor.forClass(AiReviewReport.class);
        ArgumentCaptor<CandidateRule> candidateCaptor = ArgumentCaptor.forClass(CandidateRule.class);
        verify(reportMapper).insert(reportCaptor.capture());
        verify(candidateRuleMapper).insert(candidateCaptor.capture());

        assertThat(response.getDiagnosis()).contains("弱势市场");
        assertThat(response.getNeedBacktest()).isTrue();
        assertThat(response.getCandidateRules()).hasSize(1);
        assertThat(reportCaptor.getValue().getRiskDisclaimer()).isEqualTo(StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
        assertThat(reportCaptor.getValue().getSuggestions()).contains("related_rules", "suggestions", "need_backtest", "risk");

        CandidateRule candidateRule = candidateCaptor.getValue();
        assertThat(candidateRule.getCandidateCode()).startsWith("CR_20260620_");
        assertThat(candidateRule.getSource()).isEqualTo("AI");
        assertThat(candidateRule.getTargetRuleCode()).isEqualTo("R_TREND_BREAKOUT_001");
        assertThat(candidateRule.getChangeType()).isEqualTo("add_filter");
        assertThat(candidateRule.getProposedContent()).contains("market_status != weak");
        assertThat(candidateRule.getStatus()).isEqualTo(RuleLifecycleStatus.CANDIDATE.getCode());
    }

    @Test
    void savesReportButDoesNotGenerateCandidateRulesWhenSuggestionsAreEmpty() {
        LlmClient llmClient = request -> new LlmResponse("mock-llm", """
                {
                  "diagnosis": "样本不足，暂不建议生成候选规则。",
                  "related_rules": [],
                  "suggestions": [],
                  "need_backtest": false,
                  "risk": "仅作为股票研究辅助决策，不保证收益。"
                }
                """);
        AiReviewService service = new AiReviewServiceImpl(reportMapper, candidateRuleMapper, llmClient, parser, promptTemplate, objectMapper);
        when(reportMapper.insert(any(AiReviewReport.class))).thenReturn(1);

        AiReviewResponseDto response = service.review(reviewRequest());

        verify(reportMapper).insert(any(AiReviewReport.class));
        verify(candidateRuleMapper, never()).insert(any(CandidateRule.class));
        assertThat(response.getSuggestions()).isEmpty();
        assertThat(response.getCandidateRules()).isEmpty();
    }

    @Test
    void transitionsCandidateRulesThroughGovernedStatusesAndRejectsActive() {
        LlmClient llmClient = request -> new LlmResponse("mock-llm", "{}");
        AiReviewService service = new AiReviewServiceImpl(reportMapper, candidateRuleMapper, llmClient, parser, promptTemplate, objectMapper);
        CandidateRule candidateRule = CandidateRule.builder()
                .id(10L)
                .candidateCode("CR_20260620_0001")
                .status(RuleLifecycleStatus.CANDIDATE.getCode())
                .build();
        when(candidateRuleMapper.selectOne(any())).thenReturn(candidateRule);
        when(candidateRuleMapper.updateById(any(CandidateRule.class))).thenReturn(1);

        CandidateRule updated = service.transitionCandidateStatus("CR_20260620_0001", RuleLifecycleStatus.BACKTESTING.getCode());

        assertThat(updated.getStatus()).isEqualTo(RuleLifecycleStatus.BACKTESTING.getCode());
        verify(candidateRuleMapper).updateById(candidateRule);
        assertThatThrownBy(() -> service.transitionCandidateStatus("CR_20260620_0001", RuleLifecycleStatus.ACTIVE.getCode()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不能直接流转为 active");
    }

    private AiReviewRequestDto reviewRequest() {
        AiReviewRequestDto request = new AiReviewRequestDto();
        request.setDate(LocalDate.of(2026, 6, 20));
        request.setMode("daily");
        request.setSymbol("AAPL");
        request.setSignalId(42L);
        request.setPrediction("""
                {"signal":"bullish","triggered_rules":["R_TREND_BREAKOUT_001"]}
                """);
        request.setActualResult("""
                {"next_day_return":-0.032,"next_5d_return":-0.047}
                """);
        request.setMarketContext("""
                {"market_status":"weak","industry_status":"weak","news_sentiment":"negative"}
                """);
        return request;
    }
}
