package com.jx.tracker.rule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.dto.RuleGroupDetailDto;
import com.jx.tracker.domain.dto.RuleGroupMemberDto;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.RuleGroup;
import com.jx.tracker.domain.entity.RuleGroupVersion;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.RuleGroupMapper;
import com.jx.tracker.mapper.RuleGroupVersionMapper;
import com.jx.tracker.mapper.RuleStrategyMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class RuleGroupService {
    private static final Pattern CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Set<String> AGGREGATIONS = Set.of("AND", "OR", "WEIGHTED");
    private static final Set<String> STATUSES = Set.of("draft", "active", "disabled");

    private final RuleGroupMapper groupMapper;
    private final RuleGroupVersionMapper versionMapper;
    private final RuleDefinitionMapper definitionMapper;
    private final RuleStrategyMapper strategyMapper;
    private final ObjectMapper objectMapper;

    public RuleGroupService(RuleGroupMapper groupMapper, RuleGroupVersionMapper versionMapper,
                            RuleDefinitionMapper definitionMapper, RuleStrategyMapper strategyMapper,
                            ObjectMapper objectMapper) {
        this.groupMapper = groupMapper;
        this.versionMapper = versionMapper;
        this.definitionMapper = definitionMapper;
        this.strategyMapper = strategyMapper;
        this.objectMapper = objectMapper;
    }

    public List<RuleGroupDetailDto> listGroups(String status) {
        if (status != null && !status.isBlank()) {
            validateStatus(status);
        }
        return groupMapper.selectList(new LambdaQueryWrapper<RuleGroup>()
                        .eq(status != null && !status.isBlank(), RuleGroup::getStatus, status)
                        .orderByDesc(RuleGroup::getUpdatedAt))
                .stream().map(this::read).toList();
    }

    public RuleGroupDetailDto getGroup(String code) {
        return read(requireGroup(code));
    }

    public List<RuleGroupDetailDto> listVersions(String code) {
        RuleGroup group = requireGroup(code);
        return versionMapper.selectList(new LambdaQueryWrapper<RuleGroupVersion>()
                        .eq(RuleGroupVersion::getGroupId, group.getId())
                        .orderByDesc(RuleGroupVersion::getId))
                .stream().map(version -> readSnapshot(version.getSnapshotJson(), code)).toList();
    }

    public RuleGroupDetailDto getVersion(String code, String versionNo) {
        RuleGroup group = requireGroup(code);
        RuleGroupVersion version = versionMapper.selectOne(new LambdaQueryWrapper<RuleGroupVersion>()
                .eq(RuleGroupVersion::getGroupId, group.getId())
                .eq(RuleGroupVersion::getVersionNo, versionNo));
        if (version == null) {
            throw new ServiceException("规则组版本不存在：" + code + "/" + versionNo, 404);
        }
        return readSnapshot(version.getSnapshotJson(), code);
    }

    @Transactional
    public RuleGroupDetailDto create(RuleGroupDetailDto request) {
        RuleGroupDetailDto detail = normalize(request, null);
        RuleGroup row = new RuleGroup();
        copyToRow(detail, row);
        try {
            groupMapper.insert(row);
            insertVersion(row);
        } catch (DuplicateKeyException e) {
            throw badRequest("规则组编码已存在：" + detail.getGroupCode());
        }
        return detail;
    }

    @Transactional
    public RuleGroupDetailDto update(String code, RuleGroupDetailDto request) {
        RuleGroup row = requireGroup(code);
        if (request == null) {
            throw badRequest("规则组配置不能为空");
        }
        if (request.getGroupCode() != null && !code.equals(request.getGroupCode())) {
            throw badRequest("规则组编码不可修改");
        }
        if ("active".equals(row.getStatus())) {
            ensureNotUsedByActiveStrategy(code);
        }
        request.setStatus(row.getStatus());
        if (request.getAggregation() == null) {
            request.setAggregation(row.getAggregation());
        }
        if (request.getMinMatchedRules() == null) {
            request.setMinMatchedRules(row.getMinMatchedRules());
        }
        RuleGroupDetailDto detail = normalize(request, row);
        copyToRow(detail, row);
        updateExisting(row);
        return detail;
    }

    @Transactional
    public RuleGroupDetailDto copy(String code, String newCode, String newName) {
        RuleGroupDetailDto detail = getGroup(code);
        detail.setGroupCode(newCode);
        detail.setGroupName(newName);
        detail.setStatus("draft");
        detail.setVersion(null);
        return create(detail);
    }

    @Transactional
    public RuleGroupDetailDto changeStatus(String code, String status) {
        RuleGroup row = requireGroup(code);
        validateStatus(status);
        if (status.equals(row.getStatus())) {
            return read(row);
        }
        if (!"active".equals(status)) {
            ensureNotUsedByActiveStrategy(code);
        }
        RuleGroupDetailDto detail = read(row);
        detail.setStatus(status);
        if ("active".equals(status)) {
            detail = normalize(detail, row);
        } else {
            detail.setVersion(nextVersion(row.getVersion()));
        }
        copyToRow(detail, row);
        updateExisting(row);
        return detail;
    }

    private RuleGroupDetailDto normalize(RuleGroupDetailDto request, RuleGroup existing) {
        if (request == null) {
            throw badRequest("规则组配置不能为空");
        }
        String code = existing == null ? request.getGroupCode() : existing.getGroupCode();
        if (code == null || !CODE.matcher(code).matches()) {
            throw badRequest("规则组编码格式不正确");
        }
        if (request.getGroupName() == null || request.getGroupName().isBlank()
                || request.getGroupName().length() > 128) {
            throw badRequest("规则组名称不能为空且不能超过 128 字符");
        }
        String status = request.getStatus() == null ? existing == null ? "draft" : existing.getStatus()
                : request.getStatus();
        validateStatus(status);
        String aggregation = request.getAggregation() == null ? "WEIGHTED" : request.getAggregation();
        if (!AGGREGATIONS.contains(aggregation)) {
            throw badRequest("规则组聚合方式仅支持 AND、OR、WEIGHTED");
        }
        List<RuleGroupMemberDto> members = request.getMembers();
        if (members == null || members.isEmpty()) {
            throw badRequest("规则组至少需要一条正式规则");
        }
        int minMatched;
        if ("AND".equals(aggregation)) {
            minMatched = members.size();
        } else if ("OR".equals(aggregation)) {
            minMatched = 1;
        } else {
            minMatched = request.getMinMatchedRules() == null ? 1 : request.getMinMatchedRules();
            if (minMatched < 1 || minMatched > members.size()) {
                throw badRequest("最低命中数量须介于 1 和规则数量之间");
            }
        }
        Set<String> seen = new HashSet<>();
        for (RuleGroupMemberDto member : members) {
            if (member == null || member.getRuleCode() == null || !seen.add(member.getRuleCode())) {
                throw badRequest("规则组成员规则不能为空或重复");
            }
            RuleDefinition rule = definitionMapper.selectOne(new LambdaQueryWrapper<RuleDefinition>()
                    .eq(RuleDefinition::getRuleCode, member.getRuleCode()));
            if (rule == null || !RuleFormat.DROOLS.getCode().equals(rule.getRuleFormat())) {
                throw badRequest("规则组只能引用已有的正式 Drools 规则：" + member.getRuleCode());
            }
            if ("active".equals(status) && (!RuleLifecycleStatus.ACTIVE.getCode().equals(rule.getStatus())
                    || Boolean.FALSE.equals(rule.getEnabled()))) {
                throw badRequest("启用规则组前，成员规则必须已启用：" + member.getRuleCode());
            }
            member.setWeight(weight(member.getWeight()));
            member.setRequired(Boolean.TRUE.equals(member.getRequired()));
            member.setRuleVersionId(rule.getCurrentVersionId());
            member.setRuleVersionNo(rule.getCurrentVersionNo() == null
                    ? rule.getVersion() : rule.getCurrentVersionNo());
        }
        RuleGroupDetailDto result = new RuleGroupDetailDto();
        result.setGroupCode(code);
        result.setGroupName(request.getGroupName().trim());
        result.setDescription(request.getDescription());
        result.setVersion(nextVersion(existing == null ? null : existing.getVersion()));
        result.setStatus(status);
        result.setAggregation(aggregation);
        result.setMinMatchedRules(minMatched);
        result.setMembers(members);
        return result;
    }

    private void ensureNotUsedByActiveStrategy(String groupCode) {
        // The active strategy pins a group snapshot. Prevent an edit which would
        // leave its selected group in a misleading lifecycle state.
        strategyMapper.selectList(new LambdaQueryWrapper<com.jx.tracker.domain.entity.RuleStrategy>()
                        .eq(com.jx.tracker.domain.entity.RuleStrategy::getStatus, "active"))
                .forEach(strategy -> {
                    try {
                        var snapshot = objectMapper.readValue(strategy.getSnapshotJson(),
                                com.jx.tracker.domain.dto.RuleStrategyDetailDto.class);
                        if (snapshot.getGroups() != null && snapshot.getGroups().stream()
                                .anyMatch(group -> groupCode.equals(group.getGroupCode()))) {
                            throw badRequest("该规则组正被启用中的应用方案使用，请先停用应用方案");
                        }
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("启用应用方案快照无法解析：" + strategy.getStrategyCode(), e);
                    }
                });
    }

    private void copyToRow(RuleGroupDetailDto detail, RuleGroup row) {
        row.setGroupCode(detail.getGroupCode());
        row.setGroupName(detail.getGroupName());
        row.setDescription(detail.getDescription());
        row.setVersion(detail.getVersion());
        row.setStatus(detail.getStatus());
        row.setAggregation(detail.getAggregation());
        row.setMinMatchedRules(detail.getMinMatchedRules());
        row.setSnapshotJson(write(detail));
    }

    private void updateExisting(RuleGroup row) {
        if (groupMapper.updateById(row) != 1) {
            throw new ServiceException("更新规则组失败");
        }
        insertVersion(row);
    }

    private void insertVersion(RuleGroup row) {
        RuleGroupVersion version = new RuleGroupVersion();
        version.setGroupId(row.getId());
        version.setVersionNo(row.getVersion());
        version.setSnapshotJson(row.getSnapshotJson());
        if (versionMapper.insert(version) != 1) {
            throw new ServiceException("保存规则组版本失败");
        }
    }

    private RuleGroup requireGroup(String code) {
        RuleGroup row = groupMapper.selectOne(new LambdaQueryWrapper<RuleGroup>()
                .eq(RuleGroup::getGroupCode, code));
        if (row == null) {
            throw new ServiceException("规则组不存在：" + code, 404);
        }
        return row;
    }

    private RuleGroupDetailDto read(RuleGroup row) {
        return readSnapshot(row.getSnapshotJson(), row.getGroupCode());
    }

    private RuleGroupDetailDto readSnapshot(String json, String code) {
        try {
            return objectMapper.readValue(json, RuleGroupDetailDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规则组快照无法解析：" + code, e);
        }
    }

    private String write(RuleGroupDetailDto detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规则组快照无法序列化", e);
        }
    }

    private BigDecimal weight(BigDecimal value) {
        BigDecimal actual = value == null ? BigDecimal.ONE : value;
        if (actual.signum() <= 0 || actual.compareTo(new BigDecimal("100")) > 0) {
            throw badRequest("成员权重必须大于 0 且不超过 100");
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
            throw new ServiceException("规则组版本格式不正确：" + previous);
        }
    }

    private ServiceException badRequest(String message) {
        return new ServiceException(message, 400);
    }
}
