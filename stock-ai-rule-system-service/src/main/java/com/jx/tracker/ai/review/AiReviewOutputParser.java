package com.jx.tracker.ai.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.dto.AiReviewResponseDto;
import com.jx.tracker.exception.ServiceException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;

@Component
public class AiReviewOutputParser {

    private final ObjectMapper objectMapper;

    public AiReviewOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AiReviewResponseDto parse(String content) {
        String json = extractJson(content);
        try {
            JsonNode root = objectMapper.readTree(json);
            requireField(root, "diagnosis");
            requireField(root, "related_rules");
            requireField(root, "suggestions");
            requireField(root, "need_backtest");
            requireField(root, "risk");

            AiReviewResponseDto response = objectMapper.treeToValue(root, AiReviewResponseDto.class);
            if (!StringUtils.hasText(response.getDiagnosis())) {
                throw new ServiceException("AI 复盘输出缺少 diagnosis");
            }
            if (!StringUtils.hasText(response.getRisk())) {
                throw new ServiceException("AI 复盘输出缺少 risk");
            }
            if (response.getNeedBacktest() == null) {
                throw new ServiceException("AI 复盘输出缺少 need_backtest");
            }
            if (response.getRelatedRules() == null) {
                response.setRelatedRules(new ArrayList<>());
            }
            if (response.getSuggestions() == null) {
                response.setSuggestions(new ArrayList<>());
            }
            if (response.getCandidateRules() == null) {
                response.setCandidateRules(new ArrayList<>());
            }
            response.setRisk(StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
            return response;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("AI 复盘输出必须是结构化 JSON", e);
        }
    }

    private String extractJson(String content) {
        if (!StringUtils.hasText(content)) {
            throw new ServiceException("AI 复盘输出必须是结构化 JSON");
        }
        String json = content.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new ServiceException("AI 复盘输出必须是结构化 JSON");
        }
        return json;
    }

    private void requireField(JsonNode root, String fieldName) {
        if (!root.has(fieldName) || root.get(fieldName).isNull()) {
            throw new ServiceException("AI 复盘输出必须包含 " + fieldName);
        }
    }
}
