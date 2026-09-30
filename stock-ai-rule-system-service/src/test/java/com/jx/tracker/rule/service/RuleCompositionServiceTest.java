package com.jx.tracker.rule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.dto.RuleGroupDetailDto;
import com.jx.tracker.domain.dto.RuleGroupMemberDto;
import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.domain.dto.RuleStrategyGroupDto;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.RuleGroup;
import com.jx.tracker.domain.entity.RuleGroupVersion;
import com.jx.tracker.domain.entity.RuleStrategy;
import com.jx.tracker.domain.entity.RuleStrategyVersion;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.RuleGroupMapper;
import com.jx.tracker.mapper.RuleGroupVersionMapper;
import com.jx.tracker.mapper.RuleStrategyMapper;
import com.jx.tracker.mapper.RuleStrategyVersionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleCompositionServiceTest {
    private final RuleGroupMapper groupMapper = mock(RuleGroupMapper.class);
    private final RuleGroupVersionMapper groupVersionMapper = mock(RuleGroupVersionMapper.class);
    private final RuleStrategyMapper strategyMapper = mock(RuleStrategyMapper.class);
    private final RuleStrategyVersionMapper strategyVersionMapper = mock(RuleStrategyVersionMapper.class);
    private final RuleDefinitionMapper definitionMapper = mock(RuleDefinitionMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuleGroupService groupService = new RuleGroupService(groupMapper, groupVersionMapper,
            definitionMapper, strategyMapper, objectMapper);
    private final RuleStrategyService strategyService = new RuleStrategyService(strategyMapper,
            strategyVersionMapper, groupService, definitionMapper, objectMapper);

    @Test
    void groupCreationStoresImmutableVersionWithRuleVersionReference() throws Exception {
        RuleDefinition definition = new RuleDefinition();
        definition.setRuleCode("R_TREND");
        definition.setRuleFormat("drools");
        definition.setStatus("active");
        definition.setEnabled(true);
        definition.setCurrentVersionId(42L);
        definition.setCurrentVersionNo("v3");
        when(definitionMapper.selectOne(any())).thenReturn(definition);
        when(groupMapper.insert(any(RuleGroup.class))).thenAnswer(invocation -> {
            RuleGroup row = invocation.getArgument(0);
            row.setId(9L);
            return 1;
        });
        when(groupVersionMapper.insert(any(RuleGroupVersion.class))).thenReturn(1);

        RuleGroupDetailDto result = groupService.create(group("G_TREND", "active", "R_TREND"));

        assertThat(result.getVersion()).isEqualTo("v1");
        assertThat(result.getMembers().getFirst().getRuleVersionId()).isEqualTo(42L);
        ArgumentCaptor<RuleGroupVersion> versionCaptor = ArgumentCaptor.forClass(RuleGroupVersion.class);
        verify(groupVersionMapper).insert(versionCaptor.capture());
        RuleGroupVersion savedVersion = versionCaptor.getValue();
        assertThat(savedVersion.getGroupId()).isEqualTo(9L);
        assertThat(savedVersion.getVersionNo()).isEqualTo("v1");
        RuleGroupDetailDto snapshot = objectMapper.readValue(savedVersion.getSnapshotJson(), RuleGroupDetailDto.class);
        assertThat(snapshot.getMembers().getFirst().getRuleVersionNo()).isEqualTo("v3");
    }

    @Test
    void activeGroupCannotReferenceDisabledRule() {
        RuleDefinition definition = new RuleDefinition();
        definition.setRuleCode("R_TREND");
        definition.setRuleFormat("drools");
        definition.setStatus("disabled");
        when(definitionMapper.selectOne(any())).thenReturn(definition);

        assertThatThrownBy(() -> groupService.create(group("G_TREND", "active", "R_TREND")))
                .isInstanceOf(ServiceException.class).hasMessageContaining("成员规则必须已启用");
        verify(groupMapper, never()).insert(any(RuleGroup.class));
    }

    @Test
    void strategyPreservesGroupSnapshotsAndAllowsSharedRuleWithDedupAtExecution() throws Exception {
        RuleGroupDetailDto first = group("G_TREND", "active", "R_SHARED");
        first.setVersion("v2");
        RuleGroupDetailDto second = group("G_VOLUME", "active", "R_SHARED");
        second.setVersion("v4");
        when(groupMapper.selectOne(any())).thenReturn(row(first), row(second));
        RuleDefinition definition = new RuleDefinition();
        definition.setRuleCode("R_SHARED");
        definition.setRuleFormat("drools");
        definition.setStatus("active");
        definition.setEnabled(true);
        when(definitionMapper.selectOne(any())).thenReturn(definition);
        when(strategyMapper.selectList(any())).thenReturn(List.of());
        when(strategyMapper.insert(any(RuleStrategy.class))).thenAnswer(invocation -> {
            RuleStrategy row = invocation.getArgument(0);
            row.setId(12L);
            return 1;
        });
        when(strategyVersionMapper.insert(any(RuleStrategyVersion.class))).thenReturn(1);

        RuleStrategyDetailDto result = strategyService.create(strategy("S_COMBINED", "active",
                "G_TREND", "G_VOLUME"));

        assertThat(result.getVersion()).isEqualTo("v1");
        assertThat(result.getGroups()).extracting(RuleStrategyGroupDto::getGroupVersion)
                .containsExactly("v2", "v4");
        ArgumentCaptor<RuleStrategyVersion> captor = ArgumentCaptor.forClass(RuleStrategyVersion.class);
        verify(strategyVersionMapper).insert(captor.capture());
        RuleStrategyDetailDto snapshot = objectMapper.readValue(captor.getValue().getSnapshotJson(),
                RuleStrategyDetailDto.class);
        assertThat(snapshot.getGroups().getFirst().getGroup().getMembers().getFirst().getRuleCode())
                .isEqualTo("R_SHARED");
        assertThat(snapshot.getGroups().get(1).getGroupVersion()).isEqualTo("v4");
    }

    @Test
    void activatingStrategyVersionsThePreviousActiveStrategyAsDisabled() throws Exception {
        RuleGroupDetailDto selectedGroup = group("G_TREND", "active", "R_TREND");
        selectedGroup.setVersion("v2");
        when(groupMapper.selectOne(any())).thenReturn(row(selectedGroup));
        RuleDefinition definition = new RuleDefinition();
        definition.setRuleFormat("drools");
        definition.setStatus("active");
        definition.setEnabled(true);
        when(definitionMapper.selectOne(any())).thenReturn(definition);
        RuleStrategyDetailDto oldDetail = strategy("S_OLD", "active", "G_TREND");
        oldDetail.setVersion("v3");
        RuleStrategy previous = new RuleStrategy();
        previous.setId(20L);
        previous.setStrategyCode("S_OLD");
        previous.setVersion("v3");
        previous.setStatus("active");
        previous.setSnapshotJson(objectMapper.writeValueAsString(oldDetail));
        when(strategyMapper.selectList(any())).thenReturn(List.of(previous));
        when(strategyMapper.updateById(any(RuleStrategy.class))).thenReturn(1);
        when(strategyVersionMapper.insert(any(RuleStrategyVersion.class))).thenReturn(1);
        when(strategyMapper.insert(any(RuleStrategy.class))).thenAnswer(invocation -> {
            RuleStrategy row = invocation.getArgument(0);
            row.setId(21L);
            return 1;
        });

        strategyService.create(strategy("S_NEW", "active", "G_TREND"));

        assertThat(previous.getStatus()).isEqualTo("disabled");
        assertThat(previous.getVersion()).isEqualTo("v4");
        ArgumentCaptor<RuleStrategyVersion> versions = ArgumentCaptor.forClass(RuleStrategyVersion.class);
        org.mockito.Mockito.verify(strategyVersionMapper, org.mockito.Mockito.times(2)).insert(versions.capture());
        assertThat(versions.getAllValues()).extracting(RuleStrategyVersion::getVersionNo)
                .containsExactly("v4", "v1");
    }

    private RuleGroup row(RuleGroupDetailDto detail) throws Exception {
        RuleGroup row = new RuleGroup();
        row.setGroupCode(detail.getGroupCode());
        row.setStatus(detail.getStatus());
        row.setSnapshotJson(objectMapper.writeValueAsString(detail));
        return row;
    }

    private RuleGroupDetailDto group(String code, String status, String ruleCode) {
        RuleGroupMemberDto member = new RuleGroupMemberDto();
        member.setRuleCode(ruleCode);
        member.setWeight(BigDecimal.ONE);
        RuleGroupDetailDto group = new RuleGroupDetailDto();
        group.setGroupCode(code);
        group.setGroupName(code);
        group.setStatus(status);
        group.setAggregation("WEIGHTED");
        group.setMinMatchedRules(1);
        group.setMembers(List.of(member));
        return group;
    }

    private RuleStrategyDetailDto strategy(String code, String status, String... groupCodes) {
        RuleStrategyDetailDto result = new RuleStrategyDetailDto();
        result.setStrategyCode(code);
        result.setStrategyName(code);
        result.setStatus(status);
        result.setGroups(java.util.Arrays.stream(groupCodes).map(groupCode -> {
            RuleStrategyGroupDto group = new RuleStrategyGroupDto();
            group.setGroupCode(groupCode);
            group.setWeight(BigDecimal.ONE);
            return group;
        }).toList());
        return result;
    }
}
