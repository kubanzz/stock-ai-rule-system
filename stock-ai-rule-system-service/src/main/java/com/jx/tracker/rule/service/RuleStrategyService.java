package com.jx.tracker.rule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.dto.RuleGroupDetailDto;
import com.jx.tracker.domain.dto.RuleGroupMemberDto;
import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.domain.dto.RuleStrategyGroupDto;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.RuleStrategy;
import com.jx.tracker.domain.entity.RuleStrategyVersion;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.RuleStrategyMapper;
import com.jx.tracker.mapper.RuleStrategyVersionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class RuleStrategyService {
    private static final Pattern CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Set<String> STATUSES = Set.of("draft", "active", "disabled");

    private final RuleStrategyMapper strategyMapper;
    private final RuleStrategyVersionMapper versionMapper;
    private final RuleGroupService groupService;
    private final RuleDefinitionMapper definitionMapper;
    private final ObjectMapper objectMapper;

    public RuleStrategyService(RuleStrategyMapper strategyMapper,
                               RuleStrategyVersionMapper versionMapper,
                               RuleGroupService groupService,
                               RuleDefinitionMapper definitionMapper,
                               ObjectMapper objectMapper) {
        this.strategyMapper = strategyMapper;
        this.versionMapper = versionMapper;
        this.groupService = groupService;
        this.definitionMapper = definitionMapper;
        this.objectMapper = objectMapper;
    }

    public List<RuleStrategyDetailDto> listStrategies(String status) {
        if (status != null && !status.isBlank()) {
            validateStatus(status);
        }
        return strategyMapper.selectList(new LambdaQueryWrapper<RuleStrategy>()
                        .eq(status != null && !status.isBlank(), RuleStrategy::getStatus, status)
                        .orderByDesc(RuleStrategy::getUpdatedAt))
                .stream().map(this::read).toList();
    }

    public RuleStrategyDetailDto getStrategy(String code) {
        return read(requireStrategy(code));
    }

    public List<RuleStrategyDetailDto> listVersions(String code) {
        RuleStrategy strategy = requireStrategy(code);
        return versionMapper.selectList(new LambdaQueryWrapper<RuleStrategyVersion>()
                        .eq(RuleStrategyVersion::getStrategyId, strategy.getId())
                        .orderByDesc(RuleStrategyVersion::getId))
                .stream().map(version -> readSnapshot(version.getSnapshotJson(), code)).toList();
    }

    public RuleStrategyDetailDto getVersion(String code, String versionNo) {
        RuleStrategy strategy = requireStrategy(code);
        RuleStrategyVersion version = versionMapper.selectOne(new LambdaQueryWrapper<RuleStrategyVersion>()
                .eq(RuleStrategyVersion::getStrategyId, strategy.getId())
                .eq(RuleStrategyVersion::getVersionNo, versionNo));
        if (version == null) {
            throw new ServiceException("应用方案版本不存在：" + code + "/" + versionNo, 404);
        }
        return readSnapshot(version.getSnapshotJson(), code);
    }

    public RuleStrategyDetailDto getActiveStrategy() {
        RuleStrategy active = strategyMapper.selectOne(new LambdaQueryWrapper<RuleStrategy>()
                .eq(RuleStrategy::getStatus, "active"));
        return active == null ? null : read(active);
    }

    /** Keep an active application's rule set executable when managing a formal rule. */
    public void assertCanDeactivateRule(String ruleCode) {
        RuleStrategyDetailDto active = getActiveStrategy();
        if (active == null || active.getGroups() == null) {
            return;
        }
        boolean referenced = active.getGroups().stream()
                .filter(selected -> selected != null && selected.getGroup() != null
                        && selected.getGroup().getMembers() != null)
                .flatMap(selected -> selected.getGroup().getMembers().stream())
                .anyMatch(member -> member != null && ruleCode.equals(member.getRuleCode()));
        if (referenced) {
            throw badRequest("正式规则 " + ruleCode + " 正被启用中的应用方案 "
                    + active.getStrategyCode() + " 使用，请先停用或调整该应用方案");
        }
    }

    @Transactional
    public RuleStrategyDetailDto create(RuleStrategyDetailDto request) {
        RuleStrategyDetailDto detail = normalize(request, null);
        if ("active".equals(detail.getStatus())) {
            deactivateCurrent(null);
        }
        RuleStrategy row = new RuleStrategy();
        copyToRow(detail, row);
        try {
            strategyMapper.insert(row);
            insertVersion(row);
        } catch (DuplicateKeyException e) {
            throw badRequest("应用方案编码已存在或并发启用冲突：" + detail.getStrategyCode());
        }
        return detail;
    }

    @Transactional
    public RuleStrategyDetailDto update(String code, RuleStrategyDetailDto request) {
        RuleStrategy row = requireStrategy(code);
        if (request == null) {
            throw badRequest("应用方案配置不能为空");
        }
        if (request.getStrategyCode() != null && !code.equals(request.getStrategyCode())) {
            throw badRequest("应用方案编码不可修改");
        }
        request.setStatus(row.getStatus());
        if (request.getBullishThreshold() == null) {
            request.setBullishThreshold(row.getBullishThreshold());
        }
        if (request.getBearishThreshold() == null) {
            request.setBearishThreshold(row.getBearishThreshold());
        }
        if (request.getRiskThreshold() == null) {
            request.setRiskThreshold(row.getRiskThreshold());
        }
        RuleStrategyDetailDto detail = normalize(request, row);
        if ("active".equals(detail.getStatus())) {
            deactivateCurrent(code);
        }
        copyToRow(detail, row);
        updateExisting(row);
        return detail;
    }

    @Transactional
    public RuleStrategyDetailDto copy(String code, String newCode, String newName) {
        RuleStrategyDetailDto detail = getStrategy(code);
        detail.setStrategyCode(newCode);
        detail.setStrategyName(newName);
        detail.setStatus("draft");
        detail.setVersion(null);
        return create(detail);
    }

    @Transactional
    public RuleStrategyDetailDto changeStatus(String code, String status) {
        RuleStrategy row = requireStrategy(code);
        validateStatus(status);
        if (status.equals(row.getStatus())) {
            return read(row);
        }
        RuleStrategyDetailDto detail = read(row);
        detail.setStatus(status);
        if ("active".equals(status)) {
            detail = normalize(detail, row);
            deactivateCurrent(code);
        } else {
            detail.setVersion(nextVersion(row.getVersion()));
        }
        copyToRow(detail, row);
        updateExisting(row);
        return detail;
    }

    private RuleStrategyDetailDto normalize(RuleStrategyDetailDto request, RuleStrategy existing) {
        if (request == null) {
            throw badRequest("应用方案配置不能为空");
        }
        String code = existing == null ? request.getStrategyCode() : existing.getStrategyCode();
        if (code == null || !CODE.matcher(code).matches()) {
            throw badRequest("应用方案编码格式不正确");
        }
        if (request.getStrategyName() == null || request.getStrategyName().isBlank()
                || request.getStrategyName().length() > 128) {
            throw badRequest("应用方案名称不能为空且不能超过 128 字符");
        }
        String status = request.getStatus() == null ? existing == null ? "draft" : existing.getStatus()
                : request.getStatus();
        validateStatus(status);
        List<RuleStrategyGroupDto> groups = request.getGroups();
        if (groups == null || groups.isEmpty()) {
            throw badRequest("应用方案至少需要一个规则组");
        }
        Set<String> groupCodes = new HashSet<>();
        for (RuleStrategyGroupDto selected : groups) {
            if (selected == null || selected.getGroupCode() == null
                    || !groupCodes.add(selected.getGroupCode())) {
                throw badRequest("应用方案中的规则组不能为空或重复");
            }
            RuleGroupDetailDto group = groupService.getGroup(selected.getGroupCode());
            if ("active".equals(status) && !"active".equals(group.getStatus())) {
                throw badRequest("启用应用方案前，规则组必须已启用：" + selected.getGroupCode());
            }
            selected.setWeight(weight(selected.getWeight()));
            selected.setRequired(Boolean.TRUE.equals(selected.getRequired()));
            selected.setGroupVersion(group.getVersion());
            selected.setGroup(group);
            for (RuleGroupMemberDto member : group.getMembers()) {
                if ("active".equals(status)) {
                    RuleDefinition rule = definitionMapper.selectOne(new LambdaQueryWrapper<RuleDefinition>()
                            .eq(RuleDefinition::getRuleCode, member.getRuleCode()));
                    if (rule == null || !RuleFormat.DROOLS.getCode().equals(rule.getRuleFormat())
                            || !RuleLifecycleStatus.ACTIVE.getCode().equals(rule.getStatus())
                            || Boolean.FALSE.equals(rule.getEnabled())) {
                        throw badRequest("启用应用方案前，正式规则必须已启用：" + member.getRuleCode());
                    }
                }
            }
        }
        RuleStrategyDetailDto result = new RuleStrategyDetailDto();
        result.setStrategyCode(code);
        result.setStrategyName(request.getStrategyName().trim());
        result.setDescription(request.getDescription());
        result.setVersion(nextVersion(existing == null ? null : existing.getVersion()));
        result.setStatus(status);
        result.setBullishThreshold(threshold(request.getBullishThreshold(), "看涨", new BigDecimal("55")));
        result.setBearishThreshold(threshold(request.getBearishThreshold(), "看跌", new BigDecimal("60")));
        result.setRiskThreshold(threshold(request.getRiskThreshold(), "高风险", new BigDecimal("80")));
        result.setGroups(groups);
        return result;
    }

    private void deactivateCurrent(String exceptCode) {
        List<RuleStrategy> active = strategyMapper.selectList(new LambdaQueryWrapper<RuleStrategy>()
                .eq(RuleStrategy::getStatus, "active"));
        for (RuleStrategy previous : active) {
            if (previous.getStrategyCode().equals(exceptCode)) {
                continue;
            }
            RuleStrategyDetailDto old = read(previous);
            old.setStatus("disabled");
            old.setVersion(nextVersion(previous.getVersion()));
            copyToRow(old, previous);
            updateExisting(previous);
        }
    }

    private void copyToRow(RuleStrategyDetailDto detail, RuleStrategy row) {
        row.setStrategyCode(detail.getStrategyCode());
        row.setStrategyName(detail.getStrategyName());
        row.setDescription(detail.getDescription());
        row.setVersion(detail.getVersion());
        row.setStatus(detail.getStatus());
        row.setBullishThreshold(detail.getBullishThreshold());
        row.setBearishThreshold(detail.getBearishThreshold());
        row.setRiskThreshold(detail.getRiskThreshold());
        row.setSnapshotJson(write(detail));
    }

    private void updateExisting(RuleStrategy row) {
        try {
            if (strategyMapper.updateById(row) != 1) {
                throw new ServiceException("更新应用方案失败");
            }
            insertVersion(row);
        } catch (DuplicateKeyException e) {
            throw badRequest("应用方案版本或启用状态发生并发冲突，请重试");
        }
    }

    private void insertVersion(RuleStrategy row) {
        RuleStrategyVersion version = new RuleStrategyVersion();
        version.setStrategyId(row.getId());
        version.setVersionNo(row.getVersion());
        version.setSnapshotJson(row.getSnapshotJson());
        if (versionMapper.insert(version) != 1) {
            throw new ServiceException("保存应用方案版本失败");
        }
    }

    private RuleStrategy requireStrategy(String code) {
        RuleStrategy row = strategyMapper.selectOne(new LambdaQueryWrapper<RuleStrategy>()
                .eq(RuleStrategy::getStrategyCode, code));
        if (row == null) {
            throw new ServiceException("应用方案不存在：" + code, 404);
        }
        return row;
    }

    private RuleStrategyDetailDto read(RuleStrategy row) {
        return readSnapshot(row.getSnapshotJson(), row.getStrategyCode());
    }

    private RuleStrategyDetailDto readSnapshot(String json, String code) {
        try {
            return objectMapper.readValue(json, RuleStrategyDetailDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("应用方案快照无法解析：" + code, e);
        }
    }

    private String write(RuleStrategyDetailDto detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("应用方案快照无法序列化", e);
        }
    }

    private BigDecimal weight(BigDecimal value) {
        BigDecimal actual = value == null ? BigDecimal.ONE : value;
        if (actual.signum() <= 0 || actual.compareTo(new BigDecimal("100")) > 0) {
            throw badRequest("规则组权重必须大于 0 且不超过 100");
        }
        return actual;
    }

    private BigDecimal threshold(BigDecimal value, String name, BigDecimal fallback) {
        BigDecimal actual = value == null ? fallback : value;
        if (actual.signum() <= 0 || actual.compareTo(new BigDecimal("100")) > 0) {
            throw badRequest(name + "阈值须大于 0 且不超过 100");
        }
        return actual;
    }

    private void validateStatus(String status) {
        if (!STATUSES.contains(status)) {
            throw badRequest("状态仅支持 draft、active、disabled");
        }
    }

    private String nextVersion(String previous) {
        if (previous == null) {
            return "v1";
        }
        try {
            return "v" + (Integer.parseInt(previous.substring(1)) + 1);
        } catch (RuntimeException e) {
            throw new ServiceException("应用方案版本格式不正确：" + previous);
        }
    }

    private ServiceException badRequest(String message) {
        return new ServiceException(message, 400);
    }
}
