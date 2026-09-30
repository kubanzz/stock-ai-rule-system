package com.jx.tracker.rule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.dto.RuleOperationLogDto;
import com.jx.tracker.domain.dto.RulePublishRequestDto;
import com.jx.tracker.domain.dto.RulePublishResultDto;
import com.jx.tracker.domain.dto.RuleVersionDto;
import com.jx.tracker.domain.entity.CandidateRule;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.RuleOperationLog;
import com.jx.tracker.domain.entity.RuleVersion;
import com.jx.tracker.domain.enums.BacktestStatus;
import com.jx.tracker.domain.enums.CandidateRuleStatus;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.domain.enums.RuleObjectType;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleVersionApprovalStatus;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.CandidateRuleMapper;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.RuleOperationLogMapper;
import com.jx.tracker.mapper.RuleVersionMapper;
import com.jx.tracker.rule.engine.JsonRuleToDroolsCompiler;
import com.jx.tracker.rule.engine.DroolsRuleEngineExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RulePublishServiceImpl implements RulePublishService {

    private static final Pattern VERSION_PATTERN = Pattern.compile("^v?(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROOLS_CONTENT = Pattern.compile(
            "(?ms)^\\s*rule\\s+(?:\"[^\"]+\"|'[^']+'|[A-Za-z0-9_.:-]+).*?\\bwhen\\b.*?\\bthen\\b");
    private static final String MANUAL_PUBLISH_SOURCE = "manual_publish";
    private static final String PUBLISH_OPERATION = "publish";
    private static final String ROLLBACK_OPERATION = "rollback";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> FORBIDDEN_OPERATOR_NAMES = Set.of(
            "ai",
            "ai_bot",
            "system_ai",
            "system",
            "scheduler",
            "task"
    );
    private static final Set<String> PRODUCTION_RULE_TYPES = Set.of(
            "technical", "trend", "risk", "risk_guard", "sentiment");

    private final CandidateRuleMapper candidateRuleMapper;
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;
    private final RuleOperationLogMapper operationLogMapper;
    private final JsonRuleToDroolsCompiler jsonRuleToDroolsCompiler;
    private final DroolsRuleEngineExecutor droolsRuleEngineExecutor;
    private final RuleSeedDeduplicationService seedDeduplicationService;

    public RulePublishServiceImpl(CandidateRuleMapper candidateRuleMapper,
                                  RuleDefinitionMapper ruleDefinitionMapper,
                                  RuleVersionMapper ruleVersionMapper,
                                  RuleOperationLogMapper operationLogMapper) {
        this(candidateRuleMapper, ruleDefinitionMapper, ruleVersionMapper, operationLogMapper,
                new JsonRuleToDroolsCompiler(), new DroolsRuleEngineExecutor(), null);
    }

    @Autowired
    public RulePublishServiceImpl(CandidateRuleMapper candidateRuleMapper,
                                  RuleDefinitionMapper ruleDefinitionMapper,
                                  RuleVersionMapper ruleVersionMapper,
                                  RuleOperationLogMapper operationLogMapper,
                                  RuleSeedDeduplicationService seedDeduplicationService) {
        this(candidateRuleMapper, ruleDefinitionMapper, ruleVersionMapper, operationLogMapper,
                new JsonRuleToDroolsCompiler(), new DroolsRuleEngineExecutor(), seedDeduplicationService);
    }

    public RulePublishServiceImpl(CandidateRuleMapper candidateRuleMapper,
                                  RuleDefinitionMapper ruleDefinitionMapper,
                                  RuleVersionMapper ruleVersionMapper,
                                  RuleOperationLogMapper operationLogMapper,
                                  JsonRuleToDroolsCompiler jsonRuleToDroolsCompiler,
                                  DroolsRuleEngineExecutor droolsRuleEngineExecutor) {
        this(candidateRuleMapper, ruleDefinitionMapper, ruleVersionMapper, operationLogMapper,
                jsonRuleToDroolsCompiler, droolsRuleEngineExecutor, null);
    }

    public RulePublishServiceImpl(CandidateRuleMapper candidateRuleMapper,
                                  RuleDefinitionMapper ruleDefinitionMapper,
                                  RuleVersionMapper ruleVersionMapper,
                                  RuleOperationLogMapper operationLogMapper,
                                  JsonRuleToDroolsCompiler jsonRuleToDroolsCompiler,
                                  DroolsRuleEngineExecutor droolsRuleEngineExecutor,
                                  RuleSeedDeduplicationService seedDeduplicationService) {
        this.candidateRuleMapper = candidateRuleMapper;
        this.ruleDefinitionMapper = ruleDefinitionMapper;
        this.ruleVersionMapper = ruleVersionMapper;
        this.operationLogMapper = operationLogMapper;
        this.jsonRuleToDroolsCompiler = jsonRuleToDroolsCompiler;
        this.droolsRuleEngineExecutor = droolsRuleEngineExecutor;
        this.seedDeduplicationService = seedDeduplicationService;
    }

    public RulePublishServiceImpl(CandidateRuleMapper candidateRuleMapper,
                                  RuleDefinitionMapper ruleDefinitionMapper,
                                  RuleVersionMapper ruleVersionMapper,
                                  RuleOperationLogMapper operationLogMapper,
                                  JsonRuleToDroolsCompiler jsonRuleToDroolsCompiler) {
        this(candidateRuleMapper, ruleDefinitionMapper, ruleVersionMapper, operationLogMapper,
                jsonRuleToDroolsCompiler, new DroolsRuleEngineExecutor());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RulePublishResultDto publishCandidateRule(String candidateRuleId, String operator, String reason) {
        RulePublishRequestDto request = new RulePublishRequestDto();
        request.setOperator(operator);
        request.setReason(reason);
        return publishCandidateRule(candidateRuleId, request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RulePublishResultDto publishCandidateRule(String candidateRuleId, RulePublishRequestDto request) {
        if (request == null) {
            throw new ServiceException("发布信息不能为空");
        }
        String actualOperator = validateHumanOperator(request.getOperator());
        if (!StringUtils.hasText(candidateRuleId)) {
            throw new ServiceException("候选规则编码不能为空");
        }
        CandidateRule candidateRule = findCandidateRule(candidateRuleId);
        validateCandidateReadyToPublish(candidateRule);

        RuleDefinition ruleDefinition = findOrCreateRuleDefinition(candidateRule, actualOperator, request);
        String versionNo = nextVersionNo(ruleDefinition);
        String productionContent = jsonRuleToDroolsCompiler.compile(
                ruleDefinition.getRuleCode(), candidateRule.getProposedContent());
        RuleVersion version = RuleVersion.builder()
                .ruleId(ruleDefinition.getId())
                .versionNo(versionNo)
                .ruleContent(productionContent)
                .changeReason(resolveText(request.getReason(), candidateRule.getReason()))
                .source(MANUAL_PUBLISH_SOURCE)
                .approvalStatus(RuleVersionApprovalStatus.PUBLISHED.getCode())
                .publishedTime(LocalDateTime.now())
                .createdBy(actualOperator)
                .build();
        insertRuleVersion(version);

        String beforeStatus = candidateRule.getStatus();
        activateRule(ruleDefinition, version, actualOperator);
        candidateRule.setStatus(CandidateRuleStatus.PUBLISHED.getCode());
        updateCandidateRule(candidateRule);
        String auditReason = buildAuditReason(
                ruleDefinition.getRuleCode(),
                version.getId(),
                version.getVersionNo(),
                candidateRule.getCandidateCode(),
                resolveText(request.getReason(), candidateRule.getReason())
        );

        RuleOperationLog operationLog = insertOperationLog(
                RuleObjectType.CANDIDATE_RULE.getCode(),
                candidateRule.getCandidateCode(),
                PUBLISH_OPERATION,
                actualOperator,
                auditReason,
                beforeStatus,
                CandidateRuleStatus.PUBLISHED.getCode()
        );
        if (seedDeduplicationService != null) {
            seedDeduplicationService.disableSupersededSeedRule(candidateRule, ruleDefinition.getRuleContent());
        }
        return toResult(ruleDefinition, candidateRule.getCandidateCode(), CandidateRuleStatus.PUBLISHED.getCode(), version, operationLog);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RulePublishResultDto rollbackRuleVersion(String ruleId, String versionId, String operator, String reason) {
        String actualOperator = validateHumanOperator(operator);
        if (!StringUtils.hasText(ruleId)) {
            throw new ServiceException("规则编码不能为空");
        }
        if (!StringUtils.hasText(versionId)) {
            throw new ServiceException("回滚版本不能为空");
        }

        RuleDefinition ruleDefinition = findRuleDefinition(ruleId);
        RuleVersion targetVersion = findRuleVersion(ruleDefinition.getId(), versionId);
        validateRollbackTargetVersion(targetVersion);
        String beforeVersionNo = resolveText(ruleDefinition.getCurrentVersionNo(), ruleDefinition.getVersion());

        activateRule(ruleDefinition, targetVersion, actualOperator);
        if (seedDeduplicationService != null) {
            seedDeduplicationService.assertNoActiveDuplicate(
                    ruleDefinition.getRuleCode(), ruleDefinition.getRuleContent());
        }
        String auditReason = buildAuditReason(
                ruleDefinition.getRuleCode(),
                targetVersion.getId(),
                targetVersion.getVersionNo(),
                null,
                reason
        );
        RuleOperationLog operationLog = insertOperationLog(
                RuleObjectType.RULE.getCode(),
                ruleDefinition.getRuleCode(),
                ROLLBACK_OPERATION,
                actualOperator,
                auditReason,
                beforeVersionNo,
                targetVersion.getVersionNo()
        );
        return toResult(ruleDefinition, null, RuleLifecycleStatus.ACTIVE.getCode(), targetVersion, operationLog);
    }

    @Override
    public List<RuleVersionDto> listRuleVersions(String ruleCode) {
        if (!StringUtils.hasText(ruleCode)) {
            throw new ServiceException("规则编码不能为空");
        }
        RuleDefinition ruleDefinition = findRuleDefinition(ruleCode);
        return ruleVersionMapper.selectList(new LambdaQueryWrapper<RuleVersion>()
                        .eq(RuleVersion::getRuleId, ruleDefinition.getId())
                        .orderByDesc(RuleVersion::getCreatedTime)
                        .orderByDesc(RuleVersion::getId))
                .stream()
                .map(this::toVersionDto)
                .toList();
    }

    private CandidateRule findCandidateRule(String candidateRuleId) {
        CandidateRule candidateRule = candidateRuleMapper.selectOne(new LambdaQueryWrapper<CandidateRule>()
                .eq(CandidateRule::getCandidateCode, candidateRuleId));
        if (candidateRule == null) {
            throw new ServiceException("候选规则不存在: " + candidateRuleId);
        }
        return candidateRule;
    }

    private RuleDefinition findRuleDefinition(String ruleCode) {
        if (!StringUtils.hasText(ruleCode)) {
            throw new ServiceException("候选规则缺少目标规则编码");
        }
        RuleDefinition ruleDefinition = ruleDefinitionMapper.selectOne(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getRuleCode, ruleCode));
        if (ruleDefinition == null) {
            throw new ServiceException("规则不存在：" + ruleCode);
        }
        if (ruleDefinition.getId() == null) {
            throw new ServiceException("规则主键不能为空：" + ruleCode);
        }
        return ruleDefinition;
    }

    /**
     * Candidate rules are kept in {@code candidate_rule} until they are
     * approved.  Older seed data used to create a draft row in
     * {@code rule_definition} as a placeholder, but a newly generated
     * candidate must not depend on that production-directory row existing.
     * Create the production definition only as part of the human publish
     * transaction when the candidate has passed all gates.
     */
    private RuleDefinition findOrCreateRuleDefinition(CandidateRule candidateRule, String operator,
                                                      RulePublishRequestDto request) {
        String ruleCode = candidateRule.getTargetRuleCode();
        if (!StringUtils.hasText(ruleCode)) {
            throw new ServiceException("候选规则缺少目标规则编码");
        }
        RuleDefinition existing = ruleDefinitionMapper.selectOne(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getRuleCode, ruleCode));
        if (existing != null) {
            if (existing.getId() == null) {
                throw new ServiceException("规则主键不能为空：" + ruleCode);
            }
            if ("ai_candidate".equalsIgnoreCase(existing.getRuleType())
                    && (!StringUtils.hasText(request.getRuleName())
                    || !StringUtils.hasText(request.getDescription())
                    || !StringUtils.hasText(request.getRuleType()))) {
                throw new ServiceException("首次发布正式规则必须填写规则名称、规则详情和业务类型");
            }
            applyRequestedMetadata(existing, request);
            return existing;
        }

        if (!StringUtils.hasText(request.getRuleName())
                || !StringUtils.hasText(request.getDescription())
                || !StringUtils.hasText(request.getRuleType())) {
            throw new ServiceException("首次发布正式规则必须填写规则名称、规则详情和业务类型");
        }

        RuleDefinition created = RuleDefinition.builder()
                .ruleCode(ruleCode)
                .ruleContent(null)
                .ruleFormat(RuleFormat.DROOLS.getCode())
                .version("v0")
                .status(RuleLifecycleStatus.DRAFT.getCode())
                .enabled(false)
                .priority(0)
                .createdBy(operator)
                .updatedBy(operator)
                .build();
        applyRequestedMetadata(created, request);
        try {
            ensureAffected(ruleDefinitionMapper.insert(created), "保存规则定义失败");
            if (created.getId() == null) {
                throw new ServiceException("保存规则定义后未返回主键：" + ruleCode);
            }
            return created;
        } catch (DataIntegrityViolationException e) {
            // Another publish request may have created the same target between
            // the select and insert. Re-read it and continue against the
            // single production definition.
            RuleDefinition concurrent = ruleDefinitionMapper.selectOne(new LambdaQueryWrapper<RuleDefinition>()
                    .eq(RuleDefinition::getRuleCode, ruleCode));
            if (concurrent != null && concurrent.getId() != null) {
                return concurrent;
            }
            throw new ServiceException("保存规则定义冲突，请刷新后重试", e);
        }
    }

    private void applyRequestedMetadata(RuleDefinition rule, RulePublishRequestDto request) {
        if (StringUtils.hasText(request.getRuleName())) {
            String name = request.getRuleName().trim();
            if (name.length() > 128 || name.equalsIgnoreCase(rule.getRuleCode())) {
                throw new ServiceException("规则名称不能等于规则编码，且不能超过 128 字符");
            }
            rule.setRuleName(name);
        }
        if (StringUtils.hasText(request.getDescription())) {
            String description = request.getDescription().trim();
            if (description.length() > 512) {
                throw new ServiceException("规则详情不能超过 512 字符");
            }
            rule.setDescription(description);
        }
        if (StringUtils.hasText(request.getRuleType())) {
            String ruleType = request.getRuleType().trim().toLowerCase(Locale.ROOT);
            if (!PRODUCTION_RULE_TYPES.contains(ruleType)) {
                throw new ServiceException("规则类型必须是技术、趋势、风险、风险防守或情绪");
            }
            rule.setRuleType(ruleType);
        }
    }

    private RuleVersion findRuleVersion(Long ruleId, String versionId) {
        RuleVersion version = null;
        if (versionId.matches("\\d+")) {
            version = ruleVersionMapper.selectById(Long.valueOf(versionId));
        }
        if (version == null) {
            version = ruleVersionMapper.selectOne(new LambdaQueryWrapper<RuleVersion>()
                    .eq(RuleVersion::getRuleId, ruleId)
                    .eq(RuleVersion::getVersionNo, versionId));
        }
        if (version == null || !Objects.equals(version.getRuleId(), ruleId)) {
            throw new ServiceException("回滚版本不存在: " + versionId);
        }
        return version;
    }

    private void validateCandidateReadyToPublish(CandidateRule candidateRule) {
        if (CandidateRuleStatus.PUBLISHED.getCode().equals(candidateRule.getStatus())
                || RuleLifecycleStatus.ACTIVE.getCode().equals(candidateRule.getStatus())) {
            throw new ServiceException("候选规则已发布，不能重复发布");
        }
        if (!RuleLifecycleStatus.APPROVED.getCode().equals(candidateRule.getStatus())) {
            throw new ServiceException("候选规则必须先流转到 approved");
        }
        if (!BacktestStatus.SUCCESS.getCode().equals(candidateRule.getBacktestStatus())) {
            throw new ServiceException("候选规则回测未通过");
        }
        if (!RuleVersionApprovalStatus.APPROVED.getCode().equals(candidateRule.getApprovalStatus())) {
            throw new ServiceException("候选规则审核未通过");
        }
        if (!StringUtils.hasText(candidateRule.getProposedContent())) {
            throw new ServiceException("候选规则内容不能为空");
        }
    }

    private void validateRollbackTargetVersion(RuleVersion version) {
        String approvalStatus = version.getApprovalStatus();
        if (!RuleVersionApprovalStatus.PUBLISHED.getCode().equals(approvalStatus)
                && !RuleVersionApprovalStatus.APPROVED.getCode().equals(approvalStatus)) {
            throw new ServiceException("只能回滚到已发布或已审核版本");
        }
    }

    private String validateHumanOperator(String operator) {
        if (!StringUtils.hasText(operator)) {
            throw new ServiceException("操作人不能为空");
        }
        String actualOperator = operator.trim();
        String normalizedOperator = actualOperator.toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replaceAll("\\s+", "_");
        if (FORBIDDEN_OPERATOR_NAMES.contains(normalizedOperator)) {
            throw new ServiceException("系统/AI 不能直接上线生产规则");
        }
        return actualOperator;
    }

    private void activateRule(RuleDefinition ruleDefinition, RuleVersion version, String operator) {
        String originalContent = version.getRuleContent();
        boolean converted = !isDroolsContent(originalContent);
        String productionContent = toProductionContent(ruleDefinition.getRuleCode(), originalContent);
        validateProductionContent(ruleDefinition.getRuleCode(), productionContent, ruleDefinition.getPriority());
        version.setRuleContent(productionContent);
        if (converted && version.getId() != null) {
            // A rollback may target a legacy JSON version. Persist the compiled
            // production form when the mapper participates in a real transaction;
            // old direct unit-test doubles can leave the in-memory version as-is.
            int affected = ruleVersionMapper.updateById(version);
            if (affected == 0 && ruleVersionMapper != null) {
                // BaseMapper implementations return the affected-row count. A
                // zero result is still a failed persistence operation in production.
                throw new ServiceException("更新规则版本失败");
            }
        }
        ruleDefinition.setRuleContent(productionContent);
        ruleDefinition.setRuleFormat(RuleFormat.DROOLS.getCode());
        ruleDefinition.setVersion(version.getVersionNo());
        ruleDefinition.setCurrentVersionId(version.getId());
        ruleDefinition.setCurrentVersionNo(version.getVersionNo());
        ruleDefinition.setStatus(RuleLifecycleStatus.ACTIVE.getCode());
        ruleDefinition.setEnabled(true);
        ruleDefinition.setUpdatedBy(operator);
        ensureAffected(ruleDefinitionMapper.updateById(ruleDefinition), "更新规则定义失败");
    }

    private boolean isDroolsContent(String content) {
        return StringUtils.hasText(content) && DROOLS_CONTENT.matcher(content).find();
    }

    private String toProductionContent(String ruleCode, String content) {
        return isDroolsContent(content) ? content : jsonRuleToDroolsCompiler.compile(ruleCode, content);
    }

    private void validateProductionContent(String ruleCode, String content, Integer priority) {
        try {
            droolsRuleEngineExecutor.validateRuleContent(ruleCode, content, priority);
        } catch (IllegalArgumentException e) {
            throw new ServiceException("生产规则 Drools 内容校验失败：" + e.getMessage(), e);
        }
    }

    private String nextVersionNo(RuleDefinition ruleDefinition) {
        int maxVersion = parseVersionNo(ruleDefinition.getVersion());
        maxVersion = Math.max(maxVersion, parseVersionNo(ruleDefinition.getCurrentVersionNo()));
        List<RuleVersion> versions = ruleVersionMapper.selectList(new LambdaQueryWrapper<RuleVersion>()
                .eq(RuleVersion::getRuleId, ruleDefinition.getId()));
        if (versions != null) {
            for (RuleVersion version : versions) {
                maxVersion = Math.max(maxVersion, parseVersionNo(version.getVersionNo()));
            }
        }
        return "v" + (maxVersion + 1);
    }

    private int parseVersionNo(String versionNo) {
        if (!StringUtils.hasText(versionNo)) {
            return 0;
        }
        Matcher matcher = VERSION_PATTERN.matcher(versionNo.trim());
        if (!matcher.matches()) {
            return 0;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private void insertRuleVersion(RuleVersion version) {
        try {
            ensureAffected(ruleVersionMapper.insert(version), "保存规则版本失败");
        } catch (DataIntegrityViolationException e) {
            throw new ServiceException("规则版本发布冲突，请刷新后重试", e);
        }
    }

    private void updateCandidateRule(CandidateRule candidateRule) {
        ensureAffected(candidateRuleMapper.updateById(candidateRule), "更新候选规则状态失败");
    }

    private RuleOperationLog insertOperationLog(String targetType,
                                                String targetId,
                                                String operation,
                                                String operator,
                                                String reason,
                                                String beforeStatus,
                                                String afterStatus) {
        RuleOperationLog operationLog = RuleOperationLog.builder()
                .targetType(targetType)
                .targetId(targetId)
                .operation(operation)
                .operator(operator)
                .reason(reason)
                .beforeStatus(beforeStatus)
                .afterStatus(afterStatus)
                .createdTime(LocalDateTime.now())
                .build();
        ensureAffected(operationLogMapper.insert(operationLog), "保存规则操作日志失败");
        return operationLog;
    }

    private String buildAuditReason(String ruleCode,
                                    Long versionId,
                                    String versionNo,
                                    String candidateCode,
                                    String originalReason) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("ruleCode", ruleCode);
        reason.put("versionId", versionId);
        reason.put("versionNo", versionNo);
        if (StringUtils.hasText(candidateCode)) {
            reason.put("candidateCode", candidateCode);
        }
        reason.put("originalReason", originalReason);
        try {
            return OBJECT_MAPPER.writeValueAsString(reason);
        } catch (JsonProcessingException e) {
            throw new ServiceException("构建规则操作审计信息失败", e);
        }
    }

    private void ensureAffected(int affectedRows, String message) {
        if (affectedRows < 1) {
            throw new ServiceException(message);
        }
    }

    private RulePublishResultDto toResult(RuleDefinition ruleDefinition,
                                          String candidateCode,
                                          String status,
                                          RuleVersion version,
                                          RuleOperationLog operationLog) {
        RulePublishResultDto result = new RulePublishResultDto();
        result.setRuleCode(ruleDefinition.getRuleCode());
        result.setCandidateCode(candidateCode);
        result.setStatus(status);
        result.setVersion(toVersionDto(version));
        result.setOperationLog(toOperationLogDto(operationLog));
        return result;
    }

    private RuleVersionDto toVersionDto(RuleVersion version) {
        RuleVersionDto dto = new RuleVersionDto();
        dto.setId(version.getId());
        dto.setRuleId(version.getRuleId());
        dto.setVersionNo(version.getVersionNo());
        dto.setRuleContent(version.getRuleContent());
        dto.setChangeReason(version.getChangeReason());
        dto.setSource(version.getSource());
        dto.setApprovalStatus(version.getApprovalStatus());
        dto.setPublishedTime(version.getPublishedTime());
        dto.setCreatedBy(version.getCreatedBy());
        dto.setCreatedTime(version.getCreatedTime());
        return dto;
    }

    private RuleOperationLogDto toOperationLogDto(RuleOperationLog operationLog) {
        RuleOperationLogDto dto = new RuleOperationLogDto();
        dto.setId(operationLog.getId());
        dto.setTargetType(operationLog.getTargetType());
        dto.setTargetId(operationLog.getTargetId());
        dto.setOperation(operationLog.getOperation());
        dto.setOperator(operationLog.getOperator());
        dto.setReason(operationLog.getReason());
        dto.setBeforeStatus(operationLog.getBeforeStatus());
        dto.setAfterStatus(operationLog.getAfterStatus());
        dto.setCreatedTime(operationLog.getCreatedTime());
        return dto;
    }

    private String resolveText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }
}
