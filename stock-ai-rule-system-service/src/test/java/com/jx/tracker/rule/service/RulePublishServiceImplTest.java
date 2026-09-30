package com.jx.tracker.rule.service;

import com.jx.tracker.domain.dto.RulePublishResultDto;
import com.jx.tracker.domain.dto.RulePublishRequestDto;
import com.jx.tracker.domain.entity.CandidateRule;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.RuleOperationLog;
import com.jx.tracker.domain.entity.RuleVersion;
import com.jx.tracker.domain.enums.BacktestStatus;
import com.jx.tracker.domain.enums.CandidateRuleStatus;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.domain.enums.RuleVersionApprovalStatus;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.CandidateRuleMapper;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.RuleOperationLogMapper;
import com.jx.tracker.mapper.RuleVersionMapper;
import com.jx.tracker.rule.engine.DroolsRuleEngineExecutor;
import com.jx.tracker.rule.engine.JsonRuleToDroolsCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
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
                .ruleContent(previousJson())
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

        RulePublishRequestDto request = new RulePublishRequestDto();
        request.setOperator("reviewer");
        request.setReason("人工审核通过");
        request.setRuleName("趋势突破观察规则");
        request.setDescription("趋势走强时提供辅助观察信号；仅供研究和辅助决策，不构成投资建议。");
        request.setRuleType("trend");
        RulePublishResultDto result = service.publishCandidateRule("CR_20260706_0001", request);

        assertThat(result.getRuleCode()).isEqualTo("R_TREND_BREAKOUT_001");
        assertThat(result.getCandidateCode()).isEqualTo("CR_20260706_0001");
        assertThat(result.getVersion().getId()).isEqualTo(200L);
        assertThat(result.getVersion().getVersionNo()).isEqualTo("v2");
        assertThat(result.getVersion().getRuleContent())
                .contains("rule \"R_TREND_BREAKOUT_001\"")
                .contains("addBullishScore");
        assertThat(result.getVersion().getApprovalStatus()).isEqualTo(RuleVersionApprovalStatus.PUBLISHED.getCode());
        assertThat(result.getOperationLog().getId()).isEqualTo(300L);
        assertThat(result.getOperationLog().getOperation()).isEqualTo("publish");
        assertThat(result.getOperationLog().getBeforeStatus()).isEqualTo(RuleLifecycleStatus.APPROVED.getCode());
        assertThat(result.getOperationLog().getAfterStatus()).isEqualTo(CandidateRuleStatus.PUBLISHED.getCode());
        assertThat(result.getOperationLog().getReason())
                .contains("\"ruleCode\":\"R_TREND_BREAKOUT_001\"")
                .contains("\"versionId\":200")
                .contains("\"versionNo\":\"v2\"")
                .contains("\"candidateCode\":\"CR_20260706_0001\"")
                .contains("\"originalReason\":\"人工审核通过\"");

        ArgumentCaptor<RuleDefinition> ruleCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).updateById(ruleCaptor.capture());
        assertThat(ruleCaptor.getValue().getRuleContent())
                .contains("rule \"R_TREND_BREAKOUT_001\"")
                .contains("addBullishScore");
        assertThat(ruleCaptor.getValue().getVersion()).isEqualTo("v2");
        assertThat(ruleCaptor.getValue().getCurrentVersionId()).isEqualTo(200L);
        assertThat(ruleCaptor.getValue().getCurrentVersionNo()).isEqualTo("v2");
        assertThat(ruleCaptor.getValue().getStatus()).isEqualTo(RuleLifecycleStatus.ACTIVE.getCode());
        assertThat(ruleCaptor.getValue().getEnabled()).isTrue();
        assertThat(ruleCaptor.getValue().getUpdatedBy()).isEqualTo("reviewer");
        assertThat(ruleCaptor.getValue().getRuleName()).isEqualTo("趋势突破观察规则");
        assertThat(ruleCaptor.getValue().getDescription()).contains("辅助决策");
        assertThat(ruleCaptor.getValue().getRuleType()).isEqualTo("trend");

        ArgumentCaptor<CandidateRule> candidateCaptor = ArgumentCaptor.forClass(CandidateRule.class);
        verify(candidateRuleMapper).updateById(candidateCaptor.capture());
        assertThat(candidateCaptor.getValue().getStatus()).isEqualTo(CandidateRuleStatus.PUBLISHED.getCode());
    }

    @Test
    void createsProductionDefinitionWhenApprovedCandidateHasNoTargetPlaceholder() {
        CandidateRule candidate = approvedCandidate();
        candidate.setTargetRuleCode("R_NEW_AI_RULE_001");
        when(candidateRuleMapper.selectOne(any())).thenReturn(candidate);
        when(ruleDefinitionMapper.selectOne(any())).thenReturn(null);
        when(ruleDefinitionMapper.insert(any(RuleDefinition.class))).thenAnswer(invocation -> {
            RuleDefinition definition = invocation.getArgument(0);
            definition.setId(101L);
            return 1;
        });
        when(ruleVersionMapper.selectList(any())).thenReturn(List.of());
        when(ruleVersionMapper.insert(any(RuleVersion.class))).thenAnswer(invocation -> {
            RuleVersion version = invocation.getArgument(0);
            version.setId(201L);
            return 1;
        });
        when(ruleDefinitionMapper.updateById(any(RuleDefinition.class))).thenReturn(1);
        when(candidateRuleMapper.updateById(any(CandidateRule.class))).thenReturn(1);
        when(operationLogMapper.insert(any(RuleOperationLog.class))).thenAnswer(invocation -> {
            RuleOperationLog log = invocation.getArgument(0);
            log.setId(301L);
            return 1;
        });

        RulePublishRequestDto request = new RulePublishRequestDto();
        request.setOperator("reviewer");
        request.setReason("创建并上线新规则");
        request.setRuleName("趋势突破辅助规则");
        request.setDescription("短期趋势向上时提供偏看涨辅助证据；仅供研究和辅助决策，不构成投资建议。");
        request.setRuleType("trend");
        RulePublishResultDto result = service.publishCandidateRule(candidate.getCandidateCode(), request);

        assertThat(result.getRuleCode()).isEqualTo("R_NEW_AI_RULE_001");
        assertThat(result.getVersion().getRuleId()).isEqualTo(101L);
        ArgumentCaptor<RuleDefinition> insertedCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).insert(insertedCaptor.capture());
        assertThat(insertedCaptor.getValue().getRuleName()).isEqualTo("趋势突破辅助规则");
        assertThat(insertedCaptor.getValue().getDescription()).contains("辅助决策");
        assertThat(insertedCaptor.getValue().getRuleType()).isEqualTo("trend");
        ArgumentCaptor<RuleDefinition> definitionCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).updateById(definitionCaptor.capture());
        assertThat(definitionCaptor.getValue().getRuleFormat()).isEqualTo("drools");
        assertThat(definitionCaptor.getValue().getStatus()).isEqualTo(RuleLifecycleStatus.ACTIVE.getCode());
        assertThat(definitionCaptor.getValue().getEnabled()).isTrue();
    }

    @Test
    void rejectsFirstPublishWithoutBusinessMetadata() {
        CandidateRule candidate = approvedCandidate();
        candidate.setTargetRuleCode("R_NEW_AI_RULE_001");
        when(candidateRuleMapper.selectOne(any())).thenReturn(candidate);
        when(ruleDefinitionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.publishCandidateRule(candidate.getCandidateCode(), "reviewer", "上线"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("规则名称、规则详情和业务类型");
        verify(ruleDefinitionMapper, never()).insert(any(RuleDefinition.class));
    }

    @Test
    void publishCallsSeedDeduplicationWithActivatedContent() {
        CandidateRule candidate = approvedCandidate();
        RuleDefinition rule = existingRule();
        RuleSeedDeduplicationService deduplication = mock(RuleSeedDeduplicationService.class);
        RulePublishService guardedService = new RulePublishServiceImpl(
                candidateRuleMapper, ruleDefinitionMapper, ruleVersionMapper, operationLogMapper,
                new JsonRuleToDroolsCompiler(), new DroolsRuleEngineExecutor(), deduplication);
        when(candidateRuleMapper.selectOne(any())).thenReturn(candidate);
        when(ruleDefinitionMapper.selectOne(any())).thenReturn(rule);
        when(ruleVersionMapper.selectList(any())).thenReturn(List.of());
        when(ruleVersionMapper.insert(any(RuleVersion.class))).thenAnswer(invocation -> {
            RuleVersion version = invocation.getArgument(0);
            version.setId(201L);
            return 1;
        });
        when(ruleDefinitionMapper.updateById(any(RuleDefinition.class))).thenReturn(1);
        when(candidateRuleMapper.updateById(any(CandidateRule.class))).thenReturn(1);
        when(operationLogMapper.insert(any(RuleOperationLog.class))).thenReturn(1);

        guardedService.publishCandidateRule(candidate.getCandidateCode(), "reviewer", "上线");

        verify(deduplication).disableSupersededSeedRule(candidate, rule.getRuleContent());
        assertThat(candidate.getStatus()).isEqualTo(CandidateRuleStatus.PUBLISHED.getCode());
        assertThat(rule.getRuleContent()).contains("rule \"R_TREND_BREAKOUT_001\"");
    }

    @Test
    void publishPropagatesSeedDeduplicationFailureWithinTransaction() {
        CandidateRule candidate = approvedCandidate();
        RuleDefinition rule = existingRule();
        RuleSeedDeduplicationService deduplication = mock(RuleSeedDeduplicationService.class);
        RulePublishService guardedService = new RulePublishServiceImpl(
                candidateRuleMapper, ruleDefinitionMapper, ruleVersionMapper, operationLogMapper,
                new JsonRuleToDroolsCompiler(), new DroolsRuleEngineExecutor(), deduplication);
        when(candidateRuleMapper.selectOne(any())).thenReturn(candidate);
        when(ruleDefinitionMapper.selectOne(any())).thenReturn(rule);
        when(ruleVersionMapper.selectList(any())).thenReturn(List.of());
        when(ruleVersionMapper.insert(any(RuleVersion.class))).thenAnswer(invocation -> {
            RuleVersion version = invocation.getArgument(0);
            version.setId(201L);
            return 1;
        });
        when(ruleDefinitionMapper.updateById(any(RuleDefinition.class))).thenReturn(1);
        when(candidateRuleMapper.updateById(any(CandidateRule.class))).thenReturn(1);
        when(operationLogMapper.insert(any(RuleOperationLog.class))).thenReturn(1);
        doThrow(new ServiceException("去重写入失败"))
                .when(deduplication).disableSupersededSeedRule(any(CandidateRule.class), anyString());

        assertThatThrownBy(() -> guardedService.publishCandidateRule(
                candidate.getCandidateCode(), "reviewer", "上线"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("去重写入失败");
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

    @ParameterizedTest
    @ValueSource(strings = {"AI", "ai", "ai-bot", "system_ai", "system", "scheduler", "task"})
    void rejectsSystemOrAiOperatorPublishingCandidateDirectly(String operator) {
        assertThatThrownBy(() -> service.publishCandidateRule("CR_20260706_0001", operator, "自动上线"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("AI 不能直接上线生产规则");

        verifyNoInteractions(candidateRuleMapper, ruleDefinitionMapper, ruleVersionMapper, operationLogMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"published", "active"})
    void rejectsDuplicatePublishingForAlreadyPublishedOrActiveCandidate(String status) {
        CandidateRule candidate = approvedCandidate();
        candidate.setStatus(status);
        when(candidateRuleMapper.selectOne(any())).thenReturn(candidate);

        assertThatThrownBy(() -> service.publishCandidateRule(candidate.getCandidateCode(), "reviewer", "重复上线"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("候选规则已发布");

        verify(ruleVersionMapper, never()).insert(any(RuleVersion.class));
        verify(operationLogMapper, never()).insert(any(RuleOperationLog.class));
    }

    @Test
    void convertsConcurrentVersionUniqueConflictToServiceException() {
        CandidateRule candidate = approvedCandidate();
        RuleDefinition rule = existingRule();
        when(candidateRuleMapper.selectOne(any())).thenReturn(candidate);
        when(ruleDefinitionMapper.selectOne(any())).thenReturn(rule);
        when(ruleVersionMapper.selectList(any())).thenReturn(List.of());
        when(ruleVersionMapper.insert(any(RuleVersion.class))).thenThrow(new DuplicateKeyException("duplicate version"));

        assertThatThrownBy(() -> service.publishCandidateRule(candidate.getCandidateCode(), "reviewer", "上线"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("规则版本发布冲突");

        verify(ruleDefinitionMapper, never()).updateById(any(RuleDefinition.class));
        verify(candidateRuleMapper, never()).updateById(any(CandidateRule.class));
        verify(operationLogMapper, never()).insert(any(RuleOperationLog.class));
    }

    @Test
    void rejectsWhenRuleVersionInsertAffectsNoRows() {
        CandidateRule candidate = approvedCandidate();
        RuleDefinition rule = existingRule();
        when(candidateRuleMapper.selectOne(any())).thenReturn(candidate);
        when(ruleDefinitionMapper.selectOne(any())).thenReturn(rule);
        when(ruleVersionMapper.selectList(any())).thenReturn(List.of());
        when(ruleVersionMapper.insert(any(RuleVersion.class))).thenReturn(0);

        assertThatThrownBy(() -> service.publishCandidateRule(candidate.getCandidateCode(), "reviewer", "上线"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("保存规则版本失败");

        verify(ruleDefinitionMapper, never()).updateById(any(RuleDefinition.class));
    }

    @Test
    void rejectsWhenRuleDefinitionUpdateAffectsNoRows() {
        CandidateRule candidate = approvedCandidate();
        RuleDefinition rule = existingRule();
        when(candidateRuleMapper.selectOne(any())).thenReturn(candidate);
        when(ruleDefinitionMapper.selectOne(any())).thenReturn(rule);
        when(ruleVersionMapper.selectList(any())).thenReturn(List.of());
        when(ruleVersionMapper.insert(any(RuleVersion.class))).thenAnswer(invocation -> {
            RuleVersion version = invocation.getArgument(0);
            version.setId(200L);
            return 1;
        });
        when(ruleDefinitionMapper.updateById(any(RuleDefinition.class))).thenReturn(0);

        assertThatThrownBy(() -> service.publishCandidateRule(candidate.getCandidateCode(), "reviewer", "上线"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("更新规则定义失败");

        verify(candidateRuleMapper, never()).updateById(any(CandidateRule.class));
        verify(operationLogMapper, never()).insert(any(RuleOperationLog.class));
    }

    @Test
    void rejectsWhenCandidateRuleUpdateAffectsNoRows() {
        CandidateRule candidate = approvedCandidate();
        RuleDefinition rule = existingRule();
        when(candidateRuleMapper.selectOne(any())).thenReturn(candidate);
        when(ruleDefinitionMapper.selectOne(any())).thenReturn(rule);
        when(ruleVersionMapper.selectList(any())).thenReturn(List.of());
        when(ruleVersionMapper.insert(any(RuleVersion.class))).thenAnswer(invocation -> {
            RuleVersion version = invocation.getArgument(0);
            version.setId(200L);
            return 1;
        });
        when(ruleDefinitionMapper.updateById(any(RuleDefinition.class))).thenReturn(1);
        when(candidateRuleMapper.updateById(any(CandidateRule.class))).thenReturn(0);

        assertThatThrownBy(() -> service.publishCandidateRule(candidate.getCandidateCode(), "reviewer", "上线"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("更新候选规则状态失败");

        verify(operationLogMapper, never()).insert(any(RuleOperationLog.class));
    }

    @Test
    void rejectsWhenOperationLogInsertAffectsNoRows() {
        CandidateRule candidate = approvedCandidate();
        RuleDefinition rule = existingRule();
        when(candidateRuleMapper.selectOne(any())).thenReturn(candidate);
        when(ruleDefinitionMapper.selectOne(any())).thenReturn(rule);
        when(ruleVersionMapper.selectList(any())).thenReturn(List.of());
        when(ruleVersionMapper.insert(any(RuleVersion.class))).thenAnswer(invocation -> {
            RuleVersion version = invocation.getArgument(0);
            version.setId(200L);
            return 1;
        });
        when(ruleDefinitionMapper.updateById(any(RuleDefinition.class))).thenReturn(1);
        when(candidateRuleMapper.updateById(any(CandidateRule.class))).thenReturn(1);
        when(operationLogMapper.insert(any(RuleOperationLog.class))).thenReturn(0);

        assertThatThrownBy(() -> service.publishCandidateRule(candidate.getCandidateCode(), "reviewer", "上线"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("保存规则操作日志失败");
    }

    @Test
    void rollbacksRuleToExistingVersionAndWritesAuditLog() {
        RuleDefinition rule = existingRule();
        rule.setRuleContent(candidateJson());
        rule.setVersion("v2");
        rule.setCurrentVersionId(200L);
        rule.setCurrentVersionNo("v2");
        RuleVersion targetVersion = RuleVersion.builder()
                .id(90L)
                .ruleId(100L)
                .versionNo("v1")
                .ruleContent(previousJson())
                .approvalStatus(RuleVersionApprovalStatus.PUBLISHED.getCode())
                .publishedTime(LocalDateTime.now().minusDays(1))
                .build();
        when(ruleDefinitionMapper.selectOne(any())).thenReturn(rule);
        when(ruleVersionMapper.selectById(90L)).thenReturn(targetVersion);
        when(ruleVersionMapper.updateById(any(RuleVersion.class))).thenReturn(1);
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
        assertThat(result.getOperationLog().getReason())
                .contains("\"ruleCode\":\"R_TREND_BREAKOUT_001\"")
                .contains("\"versionId\":90")
                .contains("\"versionNo\":\"v1\"")
                .contains("\"originalReason\":\"回滚到稳定版本\"");

        ArgumentCaptor<RuleDefinition> ruleCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).updateById(ruleCaptor.capture());
        assertThat(ruleCaptor.getValue().getRuleContent())
                .contains("rule \"R_TREND_BREAKOUT_001\"")
                .contains("addRiskScore");
        assertThat(ruleCaptor.getValue().getVersion()).isEqualTo("v1");
        assertThat(ruleCaptor.getValue().getCurrentVersionId()).isEqualTo(90L);
        assertThat(ruleCaptor.getValue().getCurrentVersionNo()).isEqualTo("v1");
        assertThat(ruleCaptor.getValue().getStatus()).isEqualTo(RuleLifecycleStatus.ACTIVE.getCode());
        assertThat(ruleCaptor.getValue().getEnabled()).isTrue();
        assertThat(ruleCaptor.getValue().getUpdatedBy()).isEqualTo("reviewer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "rejected", "rolled_back"})
    void rejectsRollbackToUnpublishedOrUnapprovedVersion(String approvalStatus) {
        RuleDefinition rule = existingRule();
        RuleVersion targetVersion = RuleVersion.builder()
                .id(90L)
                .ruleId(100L)
                .versionNo("v1")
                .ruleContent(previousJson())
                .approvalStatus(approvalStatus)
                .build();
        when(ruleDefinitionMapper.selectOne(any())).thenReturn(rule);
        when(ruleVersionMapper.selectById(90L)).thenReturn(targetVersion);

        assertThatThrownBy(() -> service.rollbackRuleVersion("R_TREND_BREAKOUT_001", "90", "reviewer", "回滚"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("只能回滚到已发布或已审核版本");

        verify(ruleDefinitionMapper, never()).updateById(any(RuleDefinition.class));
        verify(operationLogMapper, never()).insert(any(RuleOperationLog.class));
    }

    @Test
    void allowsRollbackToApprovedVersion() {
        RuleDefinition rule = existingRule();
        RuleVersion targetVersion = RuleVersion.builder()
                .id(91L)
                .ruleId(100L)
                .versionNo("v2")
                .ruleContent(previousJson())
                .approvalStatus(RuleVersionApprovalStatus.APPROVED.getCode())
                .build();
        when(ruleDefinitionMapper.selectOne(any())).thenReturn(rule);
        when(ruleVersionMapper.selectById(91L)).thenReturn(targetVersion);
        when(ruleVersionMapper.updateById(any(RuleVersion.class))).thenReturn(1);
        when(ruleDefinitionMapper.updateById(any(RuleDefinition.class))).thenReturn(1);
        when(operationLogMapper.insert(any(RuleOperationLog.class))).thenReturn(1);

        RulePublishResultDto result = service.rollbackRuleVersion("R_TREND_BREAKOUT_001", "91", "reviewer", "回滚到审核版本");

        assertThat(result.getVersion().getApprovalStatus()).isEqualTo(RuleVersionApprovalStatus.APPROVED.getCode());
        verify(ruleDefinitionMapper).updateById(any(RuleDefinition.class));
        verify(operationLogMapper).insert(any(RuleOperationLog.class));
    }

    @Test
    void rollbackChecksForActiveDuplicateBeforeWritingAudit() {
        RuleDefinition rule = existingRule();
        RuleVersion targetVersion = RuleVersion.builder()
                .id(91L)
                .ruleId(100L)
                .versionNo("v2")
                .ruleContent(previousJson())
                .approvalStatus(RuleVersionApprovalStatus.APPROVED.getCode())
                .build();
        RuleSeedDeduplicationService deduplication = mock(RuleSeedDeduplicationService.class);
        RulePublishService guardedService = new RulePublishServiceImpl(
                candidateRuleMapper, ruleDefinitionMapper, ruleVersionMapper, operationLogMapper,
                new JsonRuleToDroolsCompiler(), new DroolsRuleEngineExecutor(), deduplication);
        when(ruleDefinitionMapper.selectOne(any())).thenReturn(rule);
        when(ruleVersionMapper.selectById(91L)).thenReturn(targetVersion);
        when(ruleVersionMapper.updateById(any(RuleVersion.class))).thenReturn(1);
        when(ruleDefinitionMapper.updateById(any(RuleDefinition.class))).thenReturn(1);
        when(operationLogMapper.insert(any(RuleOperationLog.class))).thenReturn(1);

        guardedService.rollbackRuleVersion("R_TREND_BREAKOUT_001", "91", "reviewer", "回滚");

        verify(deduplication).assertNoActiveDuplicate(
                "R_TREND_BREAKOUT_001", rule.getRuleContent());
    }

    private CandidateRule approvedCandidate() {
        return CandidateRule.builder()
                .id(10L)
                .candidateCode("CR_20260706_0001")
                .source("AI")
                .targetRuleCode("R_TREND_BREAKOUT_001")
                .changeType("add_filter")
                .proposedContent(candidateJson())
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
                .ruleContent(previousJson())
                .ruleFormat("json")
                .version("v1")
                .status(RuleLifecycleStatus.APPROVED.getCode())
                .currentVersionId(90L)
                .currentVersionNo("v1")
                .enabled(false)
                .priority(10)
                .build();
    }

    private String candidateJson() {
        return """
                {"conditions":[{"field":"short_term_trend","operator":"eq","value":"strong_up"}],"actions":{"bullish_score":25,"explanation":"候选规则"}}
                """.trim();
    }

    private String previousJson() {
        return """
                {"conditions":[{"field":"short_term_trend","operator":"eq","value":"sideways"}],"actions":{"risk_score":10,"explanation":"历史规则"}}
                """.trim();
    }
}
