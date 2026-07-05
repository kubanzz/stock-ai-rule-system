package com.jx.tracker.rule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jx.tracker.domain.dto.RuleOperationLogDto;
import com.jx.tracker.domain.dto.RulePublishResultDto;
import com.jx.tracker.domain.dto.RuleVersionDto;
import com.jx.tracker.domain.entity.CandidateRule;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.RuleOperationLog;
import com.jx.tracker.domain.entity.RuleVersion;
import com.jx.tracker.domain.enums.BacktestStatus;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.domain.enums.RuleObjectType;
import com.jx.tracker.domain.enums.RuleVersionApprovalStatus;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.CandidateRuleMapper;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.RuleOperationLogMapper;
import com.jx.tracker.mapper.RuleVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RulePublishServiceImpl implements RulePublishService {

    private static final Pattern VERSION_PATTERN = Pattern.compile("^v?(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final String MANUAL_PUBLISH_SOURCE = "manual_publish";
    private static final String PUBLISH_OPERATION = "publish";
    private static final String ROLLBACK_OPERATION = "rollback";

    private final CandidateRuleMapper candidateRuleMapper;
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;
    private final RuleOperationLogMapper operationLogMapper;

    public RulePublishServiceImpl(CandidateRuleMapper candidateRuleMapper,
                                  RuleDefinitionMapper ruleDefinitionMapper,
                                  RuleVersionMapper ruleVersionMapper,
                                  RuleOperationLogMapper operationLogMapper) {
        this.candidateRuleMapper = candidateRuleMapper;
        this.ruleDefinitionMapper = ruleDefinitionMapper;
        this.ruleVersionMapper = ruleVersionMapper;
        this.operationLogMapper = operationLogMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RulePublishResultDto publishCandidateRule(String candidateRuleId, String operator, String reason) {
        String actualOperator = validateHumanOperator(operator);
        if (!StringUtils.hasText(candidateRuleId)) {
            throw new ServiceException("候选规则编码不能为空");
        }
        CandidateRule candidateRule = findCandidateRule(candidateRuleId);
        validateCandidateReadyToPublish(candidateRule);

        RuleDefinition ruleDefinition = findRuleDefinition(candidateRule.getTargetRuleCode());
        String versionNo = nextVersionNo(ruleDefinition);
        RuleVersion version = RuleVersion.builder()
                .ruleId(ruleDefinition.getId())
                .versionNo(versionNo)
                .ruleContent(candidateRule.getProposedContent())
                .changeReason(resolveText(reason, candidateRule.getReason()))
                .source(MANUAL_PUBLISH_SOURCE)
                .approvalStatus(RuleVersionApprovalStatus.PUBLISHED.getCode())
                .publishedTime(LocalDateTime.now())
                .createdBy(actualOperator)
                .build();
        ruleVersionMapper.insert(version);

        String beforeStatus = candidateRule.getStatus();
        activateRule(ruleDefinition, version, actualOperator);
        candidateRule.setStatus(RuleLifecycleStatus.ACTIVE.getCode());
        candidateRuleMapper.updateById(candidateRule);

        RuleOperationLog operationLog = insertOperationLog(
                RuleObjectType.CANDIDATE_RULE.getCode(),
                candidateRule.getCandidateCode(),
                PUBLISH_OPERATION,
                actualOperator,
                resolveText(reason, candidateRule.getReason()),
                beforeStatus,
                RuleLifecycleStatus.ACTIVE.getCode()
        );
        return toResult(ruleDefinition, candidateRule.getCandidateCode(), RuleLifecycleStatus.ACTIVE.getCode(), version, operationLog);
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
        String beforeVersionNo = resolveText(ruleDefinition.getCurrentVersionNo(), ruleDefinition.getVersion());

        activateRule(ruleDefinition, targetVersion, actualOperator);
        RuleOperationLog operationLog = insertOperationLog(
                RuleObjectType.RULE.getCode(),
                ruleDefinition.getRuleCode(),
                ROLLBACK_OPERATION,
                actualOperator,
                reason,
                beforeVersionNo,
                targetVersion.getVersionNo()
        );
        return toResult(ruleDefinition, null, RuleLifecycleStatus.ACTIVE.getCode(), targetVersion, operationLog);
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

    private String validateHumanOperator(String operator) {
        if (!StringUtils.hasText(operator)) {
            throw new ServiceException("操作人不能为空");
        }
        String actualOperator = operator.trim();
        if ("AI".equalsIgnoreCase(actualOperator)) {
            throw new ServiceException("AI 不能直接上线生产规则");
        }
        return actualOperator;
    }

    private void activateRule(RuleDefinition ruleDefinition, RuleVersion version, String operator) {
        ruleDefinition.setRuleContent(version.getRuleContent());
        ruleDefinition.setVersion(version.getVersionNo());
        ruleDefinition.setCurrentVersionId(version.getId());
        ruleDefinition.setCurrentVersionNo(version.getVersionNo());
        ruleDefinition.setStatus(RuleLifecycleStatus.ACTIVE.getCode());
        ruleDefinition.setEnabled(true);
        ruleDefinition.setUpdatedBy(operator);
        ruleDefinitionMapper.updateById(ruleDefinition);
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
        operationLogMapper.insert(operationLog);
        return operationLog;
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
