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
    void p4CanActivateAsAuxiliaryWithoutChangingFinalVerification(String recognition) throws Exception {
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
        if ("atom".equals(recognition)) {
            rule.setRuleContent(new String(new org.springframework.core.io.ClassPathResource(
                    "rules/R_P4_VOTE_001_OPEN_GAP.drl").getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
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

        request.setResearchStatus("verified"); // Client input cannot certify final-test success.
        RuleStrategyDetailDto enabled = service.create(request);
        assertThat(enabled.getStatus()).isEqualTo("active");
        assertThat(enabled.getUsageMode()).isEqualTo("auxiliary");
        assertThat(enabled.getResearchStatus()).isEqualTo("pending_final");
        verify(strategies).insert(any(RuleStrategy.class));
        verifyNoInteractions(verification);

        request.setStatus("draft");
        RuleStrategyDetailDto draft = service.create(request);
        assertThat(draft.getStatus()).isEqualTo("draft");
        // A renamed rule body is classified when activating; draft content is not loaded.
        if (!"renamedContent".equals(recognition)) {
            assertThat(draft.getResearchStatus()).isEqualTo("pending_final");
        }
    }

    @Test
    void refusesP4ContentEditedUnderTheSameVersionLabel() {
        RuleGroupMemberDto member = new RuleGroupMemberDto(); member.setRuleCode("R_P4_VOTE_001_OPEN_GAP");
        member.setRuleVersionNo("v1");
        RuleGroupDetailDto group = new RuleGroupDetailDto(); group.setGroupCode("P4-VOTE-001");
        group.setVersion("v1"); group.setStatus("active"); group.setMembers(List.of(member));
        RuleGroupService groups = mock(RuleGroupService.class); when(groups.getGroup("P4-VOTE-001")).thenReturn(group);
        RuleDefinitionMapper definitions = mock(RuleDefinitionMapper.class);
        when(definitions.selectOne(any())).thenReturn(RuleDefinition.builder().ruleCode(member.getRuleCode())
                .ruleFormat("drools").status("active").enabled(true).version("v1").ruleContent("modified rule").build());
        RuleStrategyService service = new RuleStrategyService(mock(RuleStrategyMapper.class),
                mock(RuleStrategyVersionMapper.class), groups, definitions, new ObjectMapper());
        RuleStrategyDetailDto request = new RuleStrategyDetailDto(); request.setStrategyCode("P4-VOTE-001");
        request.setStrategyName("冻结方案"); request.setStatus("active");
        RuleStrategyGroupDto selection = new RuleStrategyGroupDto(); selection.setGroupCode("P4-VOTE-001");
        request.setGroups(List.of(selection));
        assertThatThrownBy(() -> service.create(request)).hasMessageContaining("冻结规则内容校验失败");
    }

    @Test
    void enablingSavedP4DraftRefreshesGroupAndRetainsFrozenScope() throws Exception {
        RuleGroupDetailDto group = new RuleGroupDetailDto();
        group.setGroupCode("P4-VOTE-001");group.setStatus("active");group.setVersion("v2");
        group.setMembers(List.of());
        RuleGroupService groups = mock(RuleGroupService.class);
        when(groups.getGroup("P4-VOTE-001")).thenReturn(group);
        RuleStrategyGroupDto selected = new RuleStrategyGroupDto();
        selected.setGroupCode("P4-VOTE-001");selected.setGroupVersion("v1");
        RuleStrategyDetailDto draft = new RuleStrategyDetailDto();
        draft.setStrategyCode("P4-VOTE-001");draft.setStrategyName("冻结三选二");
        draft.setStatus("draft");draft.setVersion("v1");draft.setGroups(List.of(selected));
        draft.setStockPoolType("watchlist");draft.setStockPoolCode("pit100");
        draft.setStockPoolSymbols(List.of("600418.SH", "000030.SZ"));
        ObjectMapper json = new ObjectMapper();
        RuleStrategy row = new RuleStrategy();row.setId(4L);row.setStrategyCode("P4-VOTE-001");
        row.setStatus("draft");row.setVersion("v1");row.setSnapshotJson(json.writeValueAsString(draft));
        RuleStrategyMapper strategies = mock(RuleStrategyMapper.class);
        when(strategies.selectOne(any())).thenReturn(row);
        when(strategies.updateById(any(RuleStrategy.class))).thenReturn(1);
        RuleStrategyVersionMapper versions = mock(RuleStrategyVersionMapper.class);
        when(versions.insert(any(RuleStrategyVersion.class))).thenReturn(1);
        ResearchVerificationService verification = mock(ResearchVerificationService.class);
        RuleStrategyService service = new RuleStrategyService(strategies, versions, groups,
                mock(RuleDefinitionMapper.class), json, null, null, null, verification);

        RuleStrategyDetailDto enabled = service.changeStatus("P4-VOTE-001", "active");
        assertThat(enabled.getStatus()).isEqualTo("active");
        assertThat(enabled.getVersion()).isEqualTo("v2");
        assertThat(enabled.getUsageMode()).isEqualTo("auxiliary");
        assertThat(enabled.getResearchStatus()).isEqualTo("pending_final");
        assertThat(enabled.getGroups().getFirst().getGroupVersion()).isEqualTo("v2");
        assertThat(enabled.getStockPoolSymbols()).containsExactly("600418.SH", "000030.SZ");
        verifyNoInteractions(verification);
        RuleStrategyDetailDto saved = json.readValue(row.getSnapshotJson(), RuleStrategyDetailDto.class);
        assertThat(saved.getResearchStatus()).isEqualTo("pending_final");
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
