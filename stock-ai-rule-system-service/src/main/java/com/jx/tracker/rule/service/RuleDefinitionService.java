package com.jx.tracker.rule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jx.tracker.domain.dto.RuleDefinitionUpsertDto;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.rule.engine.DroolsRuleEngineExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class RuleDefinitionService {

    private static final Pattern DROOLS_CONTENT = Pattern.compile(
            "(?ms)^\\s*rule\\s+(?:\"[^\"]+\"|'[^']+'|[A-Za-z0-9_.:-]+).*?\\bwhen\\b.*?\\bthen\\b");

    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final DroolsRuleEngineExecutor droolsRuleEngineExecutor;
    private final RuleStrategyService ruleStrategyService;
    private final RuleSeedDeduplicationService ruleSeedDeduplicationService;

    public RuleDefinitionService(RuleDefinitionMapper ruleDefinitionMapper) {
        this(ruleDefinitionMapper, new DroolsRuleEngineExecutor(), null, null);
    }

    public RuleDefinitionService(RuleDefinitionMapper ruleDefinitionMapper,
                                 DroolsRuleEngineExecutor droolsRuleEngineExecutor) {
        this(ruleDefinitionMapper, droolsRuleEngineExecutor, null, null);
    }

    public RuleDefinitionService(RuleDefinitionMapper ruleDefinitionMapper,
                                 DroolsRuleEngineExecutor droolsRuleEngineExecutor,
                                 RuleStrategyService ruleStrategyService) {
        this(ruleDefinitionMapper, droolsRuleEngineExecutor, ruleStrategyService, null);
    }

    @Autowired
    public RuleDefinitionService(RuleDefinitionMapper ruleDefinitionMapper,
                                 DroolsRuleEngineExecutor droolsRuleEngineExecutor,
                                 RuleStrategyService ruleStrategyService,
                                 RuleSeedDeduplicationService ruleSeedDeduplicationService) {
        this.ruleDefinitionMapper = ruleDefinitionMapper;
        this.droolsRuleEngineExecutor = droolsRuleEngineExecutor;
        this.ruleStrategyService = ruleStrategyService;
        this.ruleSeedDeduplicationService = ruleSeedDeduplicationService;
    }

    public RuleDefinition create(RuleDefinitionUpsertDto dto) {
        RuleDefinition rule = toEntity(dto);
        assertNoActiveDuplicate(rule);
        ruleDefinitionMapper.insert(rule);
        return rule;
    }

    public RuleDefinition update(String ruleCode, RuleDefinitionUpsertDto dto) {
        RuleDefinition existing = getByCode(ruleCode);
        // This direct-edit endpoint overwrites the current row without an archive.
        // Rules used by an enabled application must use the version publication flow.
        assertCanOverwriteRule(ruleCode);
        // Preserve lifecycle state when an unreferenced rule is edited without one.
        if (dto != null && !hasText(dto.getStatus())) {
            dto.setStatus(existing.getStatus());
        }
        RuleDefinition rule = toEntity(dto);
        rule.setId(existing.getId());
        rule.setRuleCode(ruleCode);
        if (!RuleLifecycleStatus.ACTIVE.getCode().equals(rule.getStatus())) {
            assertCanDeactivateRule(ruleCode);
        }
        assertNoActiveDuplicate(rule);
        ruleDefinitionMapper.updateById(rule);
        return rule;
    }

    public RuleDefinition enable(String ruleCode) {
        return updateStatus(ruleCode, RuleLifecycleStatus.ACTIVE.getCode());
    }

    public RuleDefinition disable(String ruleCode) {
        return updateStatus(ruleCode, RuleLifecycleStatus.DISABLED.getCode());
    }

    public List<RuleDefinition> list(String status, String ruleFormat) {
        return ruleDefinitionMapper.selectList(new LambdaQueryWrapper<RuleDefinition>()
                .eq(status != null && !status.isBlank(), RuleDefinition::getStatus, status)
                .eq(ruleFormat != null && !ruleFormat.isBlank(), RuleDefinition::getRuleFormat, ruleFormat)
                .orderByDesc(RuleDefinition::getPriority));
    }

    private RuleDefinition updateStatus(String ruleCode, String status) {
        RuleDefinition existing = getByCode(ruleCode);
        if (RuleLifecycleStatus.ACTIVE.getCode().equals(status)) {
            validateProductionRule(existing.getRuleFormat(), existing.getRuleContent());
            if (ruleSeedDeduplicationService != null) {
                ruleSeedDeduplicationService.assertNoActiveDuplicate(
                        existing.getRuleCode(), existing.getRuleContent());
            }
            existing.setEnabled(true);
        } else if (RuleLifecycleStatus.DISABLED.getCode().equals(status)) {
            assertCanDeactivateRule(ruleCode);
            existing.setEnabled(false);
        }
        existing.setStatus(status);
        ruleDefinitionMapper.updateById(existing);
        return existing;
    }

    private void assertCanOverwriteRule(String ruleCode) {
        if (ruleStrategyService == null) return;
        try {
            ruleStrategyService.assertCanDeactivateRule(ruleCode);
        } catch (ServiceException dependency) {
            throw new ServiceException("不能原地编辑启用方案正在使用的正式规则，请通过规则版本发布流程更新，"
                    + "或先停用引用方案：" + dependency.getMessage(), dependency.getCode());
        }
    }

    private void assertCanDeactivateRule(String ruleCode) {
        if (ruleStrategyService != null) {
            ruleStrategyService.assertCanDeactivateRule(ruleCode);
        }
    }

    private void assertNoActiveDuplicate(RuleDefinition rule) {
        if (ruleSeedDeduplicationService != null
                && RuleLifecycleStatus.ACTIVE.getCode().equals(rule.getStatus())) {
            ruleSeedDeduplicationService.assertNoActiveDuplicate(
                    rule.getRuleCode(), rule.getRuleContent());
        }
    }

    private RuleDefinition getByCode(String ruleCode) {
        RuleDefinition existing = ruleDefinitionMapper.selectOne(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getRuleCode, ruleCode));
        if (existing == null) {
            throw new ServiceException("规则不存在：" + ruleCode);
        }
        return existing;
    }

    private RuleDefinition toEntity(RuleDefinitionUpsertDto dto) {
        String ruleFormat = hasText(dto.getRuleFormat())
                ? dto.getRuleFormat().trim().toLowerCase(Locale.ROOT)
                : RuleFormat.DROOLS.getCode();
        String status = hasText(dto.getStatus())
                ? dto.getStatus().trim().toLowerCase(Locale.ROOT)
                : RuleLifecycleStatus.DRAFT.getCode();
        validateProductionRule(ruleFormat, dto.getRuleContent(), status,
                dto.getPriority() == null ? 0 : dto.getPriority(), dto.getRuleCode());
        Boolean enabled = null;
        if (RuleLifecycleStatus.ACTIVE.getCode().equals(status)) {
            enabled = Boolean.TRUE;
        } else if (RuleLifecycleStatus.DISABLED.getCode().equals(status)) {
            enabled = Boolean.FALSE;
        }
        return RuleDefinition.builder()
                .ruleCode(dto.getRuleCode())
                .ruleName(dto.getRuleName())
                .description(dto.getDescription())
                .ruleType(dto.getRuleType())
                .ruleContent(dto.getRuleContent())
                .ruleFormat(ruleFormat)
                .version(hasText(dto.getVersion()) ? dto.getVersion() : "v1")
                .status(status)
                .enabled(enabled)
                .priority(dto.getPriority() == null ? 0 : dto.getPriority())
                .build();
    }

    private void validateProductionRule(String ruleFormat, String ruleContent) {
        validateProductionRule(ruleFormat, ruleContent, RuleLifecycleStatus.ACTIVE.getCode(), 0, null);
    }

    private void validateProductionRule(String ruleFormat, String ruleContent, String status) {
        validateProductionRule(ruleFormat, ruleContent, status, 0, null);
    }

    private void validateProductionRule(String ruleFormat, String ruleContent, String status,
                                        Integer priority, String ruleCode) {
        if (!RuleFormat.DROOLS.getCode().equalsIgnoreCase(ruleFormat)) {
            throw new ServiceException("生产规则必须使用 Drools 格式，JSON 仅允许作为候选规则内容");
        }
        if (RuleLifecycleStatus.ACTIVE.getCode().equalsIgnoreCase(status)
                && !looksLikeDrools(ruleContent)) {
            throw new ServiceException("启用生产规则前必须提供有效 Drools 内容");
        }
        if (RuleLifecycleStatus.ACTIVE.getCode().equalsIgnoreCase(status)) {
            try {
                droolsRuleEngineExecutor.validateRuleContent(ruleCode, ruleContent, priority);
            } catch (IllegalArgumentException e) {
                throw new ServiceException("生产规则 Drools 内容校验失败：" + e.getMessage(), e);
            }
        }
    }

    private boolean looksLikeDrools(String content) {
        if (!hasText(content)) {
            return false;
        }
        return DROOLS_CONTENT.matcher(content).find();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
