package com.jx.tracker.rule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.dto.RuleGroupDetailDto;
import com.jx.tracker.domain.dto.RuleGroupMemberDto;
import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.domain.dto.RuleStrategyGroupDto;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.RuleStrategy;
import com.jx.tracker.domain.entity.RuleStrategyVersion;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.RuleStrategyMapper;
import com.jx.tracker.mapper.RuleStrategyVersionMapper;
import com.jx.tracker.verification.ResearchVerificationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuleStrategyResearchGateTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"strategy", "group", "atom", "renamedContent"})
    void p4PendingFinalCannotActivateThroughCodeOrContentCopies(String recognition) {
        RuleGroupService groups = mock(RuleGroupService.class);
        RuleGroupDetailDto group = new RuleGroupDetailDto();
        group.setGroupCode("group".equals(recognition) ? "P4-VOTE-001" : "G_COPY");
        group.setStatus("active");
        group.setVersion("v1");
        RuleGroupMemberDto member = new RuleGroupMemberDto();
        member.setRuleCode("atom".equals(recognition) ? "R_P4_VOTE_001_OPEN_GAP" : "R_COPY");
        member.setWeight(BigDecimal.ONE);
        group.setMembers(List.of(member));
        when(groups.getGroup(group.getGroupCode())).thenReturn(group);

        RuleDefinition rule = new RuleDefinition();
        rule.setRuleCode(member.getRuleCode());
        rule.setRuleFormat("drools");
        rule.setStatus("active");
        rule.setEnabled(true);
        rule.setRuleContent("renamedContent".equals(recognition)
                ? "rule \"R_COPY\" when eval(p4_vote_001_version == \"p4-vote-001-v1\") then end"
                : "rule \"R_COPY\" when then end");
        RuleDefinitionMapper definitions = mock(RuleDefinitionMapper.class);
        when(definitions.selectOne(any())).thenReturn(rule);
        RuleStrategyMapper strategies = mock(RuleStrategyMapper.class);
        RuleStrategyVersionMapper versions = mock(RuleStrategyVersionMapper.class);
        when(versions.insert(any(RuleStrategyVersion.class))).thenReturn(1);
        ResearchVerificationService verification = mock(ResearchVerificationService.class);
        RuleStrategyService service = new RuleStrategyService(strategies, versions, groups,
                definitions, new ObjectMapper(), null, null, null, verification);
        RuleStrategyDetailDto request = new RuleStrategyDetailDto();
        request.setStrategyCode("strategy".equals(recognition) ? "P4-VOTE-001" : "S_COPY");
        request.setStrategyName("待最终验证的 P4 方案");
        request.setStatus("active");
        RuleStrategyGroupDto selected = new RuleStrategyGroupDto();
        selected.setGroupCode(group.getGroupCode());
        request.setGroups(List.of(selected));

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("verified");
        verify(strategies, never()).insert(any(RuleStrategy.class));
        verify(verification).isVerifiedStrategy(request.getStrategyCode());

        request.setStatus("draft");
        assertThat(service.create(request).getStatus()).isEqualTo("draft");
        verify(strategies).insert(any(RuleStrategy.class));
    }

    @Test
    void researchStrategyCannotActivateWithoutVerifiedEvidence() {
        RuleGroupService groups = mock(RuleGroupService.class);
        RuleGroupDetailDto group = new RuleGroupDetailDto();
        group.setGroupCode("G144");
        group.setGroupName("研究组");
        group.setStatus("active");
        group.setVersion("v1");
        RuleGroupMemberDto member = new RuleGroupMemberDto();
        member.setRuleCode("R_G144_A1_MARKET_LOW");
        member.setWeight(BigDecimal.ONE);
        group.setMembers(List.of(member));
        when(groups.getGroup("G144")).thenReturn(group);

        RuleDefinition definition = new RuleDefinition();
        definition.setRuleCode(member.getRuleCode());
        definition.setRuleFormat("drools");
        definition.setStatus("active");
        definition.setEnabled(true);
        RuleDefinitionMapper definitions = mock(RuleDefinitionMapper.class);
        when(definitions.selectOne(any())).thenReturn(definition);

        RuleStrategyService service = new RuleStrategyService(
                mock(RuleStrategyMapper.class), mock(RuleStrategyVersionMapper.class), groups,
                definitions, new ObjectMapper(), null, null, null,
                mock(ResearchVerificationService.class));
        RuleStrategyDetailDto request = new RuleStrategyDetailDto();
        request.setStrategyCode("RS_G144_G118_SZ125");
        request.setStrategyName("研究方案");
        request.setStatus("active");
        RuleStrategyGroupDto selected = new RuleStrategyGroupDto();
        selected.setGroupCode("G144");
        request.setGroups(List.of(selected));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("verified");
    }
}
