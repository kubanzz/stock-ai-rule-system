package com.jx.tracker.rule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jx.tracker.domain.dto.RuleDefinitionUpsertDto;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RuleDefinitionService {

    private final RuleDefinitionMapper ruleDefinitionMapper;

    public RuleDefinitionService(RuleDefinitionMapper ruleDefinitionMapper) {
        this.ruleDefinitionMapper = ruleDefinitionMapper;
    }

    public RuleDefinition create(RuleDefinitionUpsertDto dto) {
        RuleDefinition rule = toEntity(dto);
        ruleDefinitionMapper.insert(rule);
        return rule;
    }

    public RuleDefinition update(String ruleCode, RuleDefinitionUpsertDto dto) {
        RuleDefinition existing = getByCode(ruleCode);
        RuleDefinition rule = toEntity(dto);
        rule.setId(existing.getId());
        rule.setRuleCode(ruleCode);
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
        existing.setStatus(status);
        ruleDefinitionMapper.updateById(existing);
        return existing;
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
        return RuleDefinition.builder()
                .ruleCode(dto.getRuleCode())
                .ruleName(dto.getRuleName())
                .ruleType(dto.getRuleType())
                .ruleContent(dto.getRuleContent())
                .ruleFormat(hasText(dto.getRuleFormat()) ? dto.getRuleFormat() : RuleFormat.JSON.getCode())
                .version(hasText(dto.getVersion()) ? dto.getVersion() : "v1")
                .status(hasText(dto.getStatus()) ? dto.getStatus() : RuleLifecycleStatus.DRAFT.getCode())
                .priority(dto.getPriority() == null ? 0 : dto.getPriority())
                .build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
