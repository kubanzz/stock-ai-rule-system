package com.jx.tracker.rule.service;

import com.jx.tracker.domain.dto.RulePublishResultDto;
import com.jx.tracker.domain.entity.CandidateRule;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.RuleOperationLog;
import com.jx.tracker.domain.entity.RuleVersion;
import com.jx.tracker.domain.enums.BacktestStatus;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.domain.enums.RuleVersionApprovalStatus;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.CandidateRuleMapper;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.RuleOperationLogMapper;
import com.jx.tracker.mapper.RuleVersionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RulePublishServiceImplTest {

    private final CandidateRuleMapper candidateRuleMapper = mock(CandidateRuleMapper.class);
    private final RuleDefinitionMapper ruleDefinitionMapper = mock(RuleDefinitionMapper.class);
    private final RuleVersionMapper ruleVersionMapper = mock(RuleVersionMapper.class);
    private final RuleOperationLogMapper operationLogMapper = mock(RuleOperationLogMapper.class);
    private final RulePublishService service = new RulePublishServiceImpl(
            candidateRuleMapper,
            ruleDefinitionMapper,
            ruleVersionMapper,
            operationLogMapper
    );

    @Test
    void publishesApprovedCandidateAsNewActiveRuleVersionAndWritesAuditLog() {
        CandidateRule candidate = approvedCandidate();
        RuleDefinition rule = existingRule();
        when(candidateRuleMapper.selectOne(any())).thenReturn(candidate);
        when(ruleDefinitionMapper.selectOne(any())).thenReturn(rule);
        when(ruleVersionMapper.selectList(any())).thenReturn(List.of(RuleVersion.builder()
                .id(90L)
                .ruleId(100L)
                .versionNo("v1")
                .ruleContent("old-json")
                .approvalStatus(RuleVersionApprovalStatus.PUBLISHED.getCode())
                .publishedTime(LocalDateTime.now().minusDays(1))
                .build()));
        when(ruleVersionMapper.insert(any(RuleVersion.class))).thenAnswer(invocation -> {
            RuleVersion version = invocation.getArgument(0);
            version.setId(200L);
            return 1;
        });
        when(ruleDefinitionMapper.updateById(any(RuleDefinition.class))).thenReturn(1);
        when(candidateRuleMapper.updateById(any(CandidateRule.class))).thenReturn(1);
        when(operationLogMapper.insert(any(RuleOperationLog.class))).thenAnswer(invocation -> {
            RuleOperationLog log = invocation.getArgument(0);
            log.setId(300L);
            return 1;
        });

        RulePublishResultDto result = service.publishCandidateRule("CR_20260706_0001", "reviewer", "人工审核通过");

        assertThat(result.getRuleCode()).isEqualTo("R_TREND_BREAKOUT_001");
        assertThat(result.getCandidateCode()).isEqualTo("CR_20260706_0001");
        assertThat(result.getVersion().getId()).isEqualTo(200L);
        assertThat(result.getVersion().getVersionNo()).isEqualTo("v2");
        assertThat(result.getVersion().getRuleContent()).isEqualTo("new-json");
        assertThat(result.getVersion().getApprovalStatus()).isEqualTo(RuleVersionApprovalStatus.PUBLISHED.getCode());
        assertThat(result.getOperationLog().getId()).isEqualTo(300L);
        assertThat(result.getOperationLog().getOperation()).isEqualTo("publish");
        assertThat(result.getOperationLog().getBeforeStatus()).isEqualTo(RuleLifecycleStatus.APPROVED.getCode());
        assertThat(result.getOperationLog().getAfterStatus()).isEqualTo(RuleLifecycleStatus.ACTIVE.getCode());

        ArgumentCaptor<RuleDefinition> ruleCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).updateById(ruleCaptor.capture());
        assertThat(ruleCaptor.getValue().getRuleContent()).isEqualTo("new-json");
        assertThat(ruleCaptor.getValue().getVersion()).isEqualTo("v2");
        assertThat(ruleCaptor.getValue().getCurrentVersionId()).isEqualTo(200L);
        assertThat(ruleCaptor.getValue().getCurrentVersionNo()).isEqualTo("v2");
        assertThat(ruleCaptor.getValue().getStatus()).isEqualTo(RuleLifecycleStatus.ACTIVE.getCode());
        assertThat(ruleCaptor.getValue().getEnabled()).isTrue();
        assertThat(ruleCaptor.getValue().getUpdatedBy()).isEqualTo("reviewer");

        ArgumentCaptor<CandidateRule> candidateCaptor = ArgumentCaptor.forClass(CandidateRule.class);
        verify(candidateRuleMapper).updateById(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().getStatus()).isEqualTo(RuleLifecycleStatus.ACTIVE.getCode());
    }

    @ParameterizedTest
    @CsvSource({
            "paper_trade,success,approved,候选规则必须先流转到 approved",
            "approved,failed,approved,候选规则回测未通过",
            "approved,success,pending,候选规则审核未通过"
    })
    void rejectsCandidatePublishBeforeStatusBacktestAndApprovalAreSatisfied(String status,
                                                                           String backtestStatus,
                                                                           String approvalStatus,
                                                                           String expectedMessage) {
        CandidateRule candidate = approvedCandidate();
        candidate.setStatus(status);
        candidate.setBacktestStatus(backtestStatus);
        candidate.setApprovalStatus(approvalStatus);
        when(candidateRuleMapper.selectOne(any())).thenReturn(candidate);

        assertThatThrownBy(() -> service.publishCandidateRule(candidate.getCandidateCode(), "reviewer", "上线"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(expectedMessage);

        verify(ruleVersionMapper, never()).insert(any(RuleVersion.class));
        verify(operationLogMapper, never()).insert(any(RuleOperationLog.class));
    }

    @Test
    void rejectsAiOperatorPublishingCandidateDirectly() {
        assertThatThrownBy(() -> service.publishCandidateRule("CR_20260706_0001", "AI", "自动上线"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("AI 不能直接上线生产规则");

        verifyNoInteractions(candidateRuleMapper, ruleDefinitionMapper, ruleVersionMapper, operationLogMapper);
    }

    @Test
    void rollbacksRuleToExistingVersionAndWritesAuditLog() {
        RuleDefinition rule = existingRule();
        rule.setRuleContent("new-json");
        rule.setVersion("v2");
        rule.setCurrentVersionId(200L);
        rule.setCurrentVersionNo("v2");
        RuleVersion targetVersion = RuleVersion.builder()
                .id(90L)
                .ruleId(100L)
                .versionNo("v1")
                .ruleContent("old-json")
                .approvalStatus(RuleVersionApprovalStatus.PUBLISHED.getCode())
                .publishedTime(LocalDateTime.now().minusDays(1))
                .build();
        when(ruleDefinitionMapper.selectOne(any())).thenReturn(rule);
        when(ruleVersionMapper.selectById(90L)).thenReturn(targetVersion);
        when(ruleDefinitionMapper.updateById(any(RuleDefinition.class))).thenReturn(1);
        when(operationLogMapper.insert(any(RuleOperationLog.class))).thenAnswer(invocation -> {
            RuleOperationLog log = invocation.getArgument(0);
            log.setId(301L);
            return 1;
        });

        RulePublishResultDto result = service.rollbackRuleVersion("R_TREND_BREAKOUT_001", "90", "reviewer", "回滚到稳定版本");

        assertThat(result.getRuleCode()).isEqualTo("R_TREND_BREAKOUT_001");
        assertThat(result.getVersion().getId()).isEqualTo(90L);
        assertThat(result.getVersion().getVersionNo()).isEqualTo("v1");
        assertThat(result.getOperationLog().getOperation()).isEqualTo("rollback");
        assertThat(result.getOperationLog().getBeforeStatus()).isEqualTo("v2");
        assertThat(result.getOperationLog().getAfterStatus()).isEqualTo("v1");

        ArgumentCaptor<RuleDefinition> ruleCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).updateById(ruleCaptor.capture());
        assertThat(ruleCaptor.getValue().getRuleContent()).isEqualTo("old-json");
        assertThat(ruleCaptor.getValue().getVersion()).isEqualTo("v1");
        assertThat(ruleCaptor.getValue().getCurrentVersionId()).isEqualTo(90L);
        assertThat(ruleCaptor.getValue().getCurrentVersionNo()).isEqualTo("v1");
        assertThat(ruleCaptor.getValue().getStatus()).isEqualTo(RuleLifecycleStatus.ACTIVE.getCode());
        assertThat(ruleCaptor.getValue().getEnabled()).isTrue();
        assertThat(ruleCaptor.getValue().getUpdatedBy()).isEqualTo("reviewer");
    }

    private CandidateRule approvedCandidate() {
        return CandidateRule.builder()
                .id(10L)
                .candidateCode("CR_20260706_0001")
                .source("AI")
                .targetRuleCode("R_TREND_BREAKOUT_001")
                .changeType("add_filter")
                .proposedContent("new-json")
                .reason("减少弱势市场假突破")
                .status(RuleLifecycleStatus.APPROVED.getCode())
                .backtestStatus(BacktestStatus.SUCCESS.getCode())
                .approvalStatus(RuleVersionApprovalStatus.APPROVED.getCode())
                .build();
    }

    private RuleDefinition existingRule() {
        return RuleDefinition.builder()
                .id(100L)
                .ruleCode("R_TREND_BREAKOUT_001")
                .ruleName("趋势突破")
                .ruleType("signal")
                .ruleContent("old-json")
                .ruleFormat("json")
                .version("v1")
                .status(RuleLifecycleStatus.APPROVED.getCode())
                .currentVersionId(90L)
                .currentVersionNo("v1")
                .enabled(false)
                .priority(10)
                .build();
    }
}
