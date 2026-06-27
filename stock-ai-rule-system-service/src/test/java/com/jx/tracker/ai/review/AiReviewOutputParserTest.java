package com.jx.tracker.ai.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.dto.AiReviewResponseDto;
import com.jx.tracker.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiReviewOutputParserTest {

    private final AiReviewOutputParser parser = new AiReviewOutputParser(new ObjectMapper());

    @Test
    void rejectsUnstructuredLlmOutput() {
        assertThatThrownBy(() -> parser.parse("本次误判可能与弱势市场有关"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("结构化 JSON");
    }

    @Test
    void parsesEmptySuggestionsWithoutRequiringCandidateRules() {
        String json = """
                {
                  "diagnosis": "规则表现正常，本次样本暂不建议调整。",
                  "related_rules": [],
                  "suggestions": [],
                  "need_backtest": false,
                  "risk": "样本数量不足，结论仅作辅助决策参考。"
                }
                """;

        AiReviewResponseDto response = parser.parse(json);

        assertThat(response.getDiagnosis()).contains("暂不建议调整");
        assertThat(response.getRelatedRules()).isEmpty();
        assertThat(response.getSuggestions()).isEmpty();
        assertThat(response.getNeedBacktest()).isFalse();
        assertThat(response.getRisk()).contains("辅助决策");
    }
}
