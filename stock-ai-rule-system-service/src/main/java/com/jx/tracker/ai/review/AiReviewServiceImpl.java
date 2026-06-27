package com.jx.tracker.ai.review;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.dto.AiReviewRequestDto;
import com.jx.tracker.domain.dto.AiReviewResponseDto;
import com.jx.tracker.domain.dto.AiReviewSuggestionDto;
import com.jx.tracker.domain.dto.CandidateRuleDto;
import com.jx.tracker.domain.entity.AiReviewReport;
import com.jx.tracker.domain.entity.CandidateRule;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.AiReviewReportMapper;
import com.jx.tracker.mapper.CandidateRuleMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AiReviewServiceImpl implements AiReviewService {

    private static final DateTimeFormatter CANDIDATE_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Set<String> FINAL_STATUSES = Set.of(
            RuleLifecycleStatus.APPROVED.getCode(),
            RuleLifecycleStatus.ARCHIVED.getCode()
    );

    private final AiReviewReportMapper reportMapper;
    private final CandidateRuleMapper candidateRuleMapper;
    private final LlmClient llmClient;
    private final AiReviewOutputParser parser;
    private final AiReviewPromptTemplate promptTemplate;
    private final ObjectMapper objectMapper;

    public AiReviewServiceImpl(AiReviewReportMapper reportMapper,
                               CandidateRuleMapper candidateRuleMapper,
                               LlmClient llmClient,
                               AiReviewOutputParser parser,
                               AiReviewPromptTemplate promptTemplate,
                               ObjectMapper objectMapper) {
        this.reportMapper = reportMapper;
        this.candidateRuleMapper = candidateRuleMapper;
        this.llmClient = llmClient;
        this.parser = parser;
        this.promptTemplate = promptTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiReviewResponseDto review(AiReviewRequestDto request) {
        LlmResponse llmResponse = llmClient.complete(new LlmRequest(promptTemplate.render(request)));
        AiReviewResponseDto response = parser.parse(llmResponse.content());

        AiReviewReport report = AiReviewReport.builder()
                .symbol(request.getSymbol())
                .reviewDate(resolveReviewDate(request))
                .signalId(request.getSignalId())
                .diagnosis(response.getDiagnosis())
                .suggestions(toJson(response))
                .modelName(llmResponse.modelName())
                .riskDisclaimer(StockRiskConstants.SIGNAL_RISK_DISCLAIMER)
                .build();
        reportMapper.insert(report);

        AtomicInteger sequence = new AtomicInteger(1);
        List<CandidateRuleDto> candidateRules = response.getSuggestions().stream()
                .filter(this::hasCandidateContent)
                .map(suggestion -> saveCandidateRule(request, suggestion, sequence.getAndIncrement()))
                .map(CandidateRuleDto::fromEntity)
                .toList();
        response.setCandidateRules(candidateRules);
        return response;
    }

    @Override
    public CandidateRule transitionCandidateStatus(String candidateCode, String targetStatus) {
        if (!StringUtils.hasText(candidateCode)) {
            throw new ServiceException("候选规则编码不能为空");
        }
        if (RuleLifecycleStatus.ACTIVE.getCode().equals(targetStatus)) {
            throw new ServiceException("AI 候选规则不能直接流转为 active");
        }
        if (!RuleLifecycleStatus.codes().contains(targetStatus)) {
            throw new ServiceException("不支持的候选规则状态: " + targetStatus);
        }

        CandidateRule candidateRule = candidateRuleMapper.selectOne(new LambdaQueryWrapper<CandidateRule>()
                .eq(CandidateRule::getCandidateCode, candidateCode));
        if (candidateRule == null) {
            throw new ServiceException("候选规则不存在: " + candidateCode);
        }
        validateTransition(candidateRule.getStatus(), targetStatus);
        candidateRule.setStatus(targetStatus);
        candidateRuleMapper.updateById(candidateRule);
        return candidateRule;
    }

    private CandidateRule saveCandidateRule(AiReviewRequestDto request, AiReviewSuggestionDto suggestion, int sequence) {
        CandidateRule candidateRule = CandidateRule.builder()
                .candidateCode(generateCandidateCode(resolveReviewDate(request), suggestion, sequence))
                .source("AI")
                .targetRuleCode(suggestion.getRuleId())
                .changeType(suggestion.getType())
                .originalContent(suggestion.getOriginalContent())
                .proposedContent(resolveProposedContent(suggestion))
                .reason(suggestion.getReason())
                .status(RuleLifecycleStatus.CANDIDATE.getCode())
                .build();
        candidateRuleMapper.insert(candidateRule);
        return candidateRule;
    }

    private boolean hasCandidateContent(AiReviewSuggestionDto suggestion) {
        return suggestion != null
                && (StringUtils.hasText(suggestion.getCondition()) || StringUtils.hasText(suggestion.getProposedContent()))
                && StringUtils.hasText(suggestion.getType());
    }

    private String generateCandidateCode(LocalDate reviewDate, AiReviewSuggestionDto suggestion, int sequence) {
        String stableInput = "%s|%s|%s|%s|%s".formatted(
                reviewDate,
                suggestion.getRuleId(),
                suggestion.getType(),
                resolveProposedContent(suggestion),
                sequence
        );
        int suffix = Math.abs(stableInput.hashCode() % 10_000);
        return "CR_" + reviewDate.format(CANDIDATE_DATE_FORMAT) + "_" + String.format("%04d", suffix);
    }

    private String resolveProposedContent(AiReviewSuggestionDto suggestion) {
        if (StringUtils.hasText(suggestion.getProposedContent())) {
            return suggestion.getProposedContent();
        }
        return suggestion.getCondition();
    }

    private LocalDate resolveReviewDate(AiReviewRequestDto request) {
        return request.getDate() == null ? LocalDate.now() : request.getDate();
    }

    private String toJson(AiReviewResponseDto response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new ServiceException("保存 AI 复盘结构化输出失败", e);
        }
    }

    private void validateTransition(String currentStatus, String targetStatus) {
        if (targetStatus.equals(currentStatus)) {
            return;
        }
        if (RuleLifecycleStatus.CANDIDATE.getCode().equals(currentStatus)) {
            requireTarget(targetStatus, RuleLifecycleStatus.BACKTESTING.getCode(), RuleLifecycleStatus.ARCHIVED.getCode());
            return;
        }
        if (RuleLifecycleStatus.BACKTESTING.getCode().equals(currentStatus)) {
            requireTarget(targetStatus, RuleLifecycleStatus.PAPER_TRADE.getCode(), RuleLifecycleStatus.ARCHIVED.getCode());
            return;
        }
        if (RuleLifecycleStatus.PAPER_TRADE.getCode().equals(currentStatus)) {
            requireTarget(targetStatus, RuleLifecycleStatus.APPROVED.getCode(), RuleLifecycleStatus.ARCHIVED.getCode());
            return;
        }
        if (FINAL_STATUSES.contains(currentStatus)) {
            throw new ServiceException("终态候选规则不能继续流转");
        }
        throw new ServiceException("当前状态不支持候选规则流转: " + currentStatus);
    }

    private void requireTarget(String targetStatus, String firstAllowed, String secondAllowed) {
        if (!firstAllowed.equals(targetStatus) && !secondAllowed.equals(targetStatus)) {
            throw new ServiceException("候选规则状态流转不合法: " + targetStatus);
        }
    }
}
