package com.jx.tracker.rule.service;

import com.jx.tracker.domain.dto.RuleDefinitionUpsertDto;
import com.jx.tracker.domain.dto.RuleGroupDetailDto;
import com.jx.tracker.domain.dto.RuleGroupMemberDto;
import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.domain.dto.RuleStrategyGroupDto;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.rule.engine.DroolsRuleEngineExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleDefinitionDependencyProtectionTest {
    private final RuleDefinitionMapper mapper = mock(RuleDefinitionMapper.class);
    private final DroolsRuleEngineExecutor executor = mock(DroolsRuleEngineExecutor.class);
    private final RuleStrategyService strategies = mock(RuleStrategyService.class);
    private final RuleSeedDeduplicationService deduplication = mock(RuleSeedDeduplicationService.class);
    private final RuleDefinitionService service = new RuleDefinitionService(
            mapper, executor, strategies, deduplication);

    @Test
    void disablingRuleReferencedByActiveStrategyIsRejectedBeforeMutation() {
        when(mapper.selectOne(any())).thenReturn(existing("R_TREND"));
        doThrow(new ServiceException("依赖保护", 400))
                .when(strategies).assertCanDeactivateRule("R_TREND");

        assertThatThrownBy(() -> service.disable("R_TREND"))
                .isInstanceOf(ServiceException.class).hasMessageContaining("依赖保护");
        verify(mapper, never()).updateById(any(RuleDefinition.class));
    }

    @Test
    void updateToDraftChecksActiveStrategyDependency() {
        when(mapper.selectOne(any())).thenReturn(existing("R_TREND"));
        doThrow(new ServiceException("依赖保护", 400))
                .when(strategies).assertCanDeactivateRule("R_TREND");
        RuleDefinitionUpsertDto request = updateRequest("draft");

        assertThatThrownBy(() -> service.update("R_TREND", request))
                .isInstanceOf(ServiceException.class).hasMessageContaining("依赖保护");
        verify(mapper, never()).updateById(any(RuleDefinition.class));
    }

    @Test
    void activeContentUpdateCanPublishNewVersionWithoutDependencyCheck() {
        when(mapper.selectOne(any())).thenReturn(existing("R_TREND"));

        service.update("R_TREND", updateRequest("active"));

        verify(strategies, never()).assertCanDeactivateRule(any());
        verify(mapper).updateById(any(RuleDefinition.class));
    }

    @Test
    void supersededSeedCannotBeReenabledWhilePublishedCounterpartRuns() {
        RuleDefinition seed = existing("R_DROOLS_OVERSOLD_NOTICE_001");
        seed.setStatus("disabled");
        seed.setEnabled(false);
        when(mapper.selectOne(any())).thenReturn(seed);
        doThrow(new ServiceException("已发布规则覆盖同一条件", 400))
                .when(deduplication).assertNoActiveDuplicate(
                        "R_DROOLS_OVERSOLD_NOTICE_001", validDrl());

        assertThatThrownBy(() -> service.enable("R_DROOLS_OVERSOLD_NOTICE_001"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("已发布规则覆盖同一条件");
        verify(mapper, never()).updateById(any(RuleDefinition.class));
    }

    @Test
    void activeUpdateChecksForPublishedCounterpartBeforeMutation() {
        when(mapper.selectOne(any())).thenReturn(existing("R_DROOLS_OVERSOLD_NOTICE_001"));
        RuleDefinitionUpsertDto request = updateRequest("active");
        doThrow(new ServiceException("已发布规则覆盖同一条件", 400))
                .when(deduplication).assertNoActiveDuplicate(
                        "R_DROOLS_OVERSOLD_NOTICE_001", validDrl());

        assertThatThrownBy(() -> service.update("R_DROOLS_OVERSOLD_NOTICE_001", request))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("已发布规则覆盖同一条件");
        verify(mapper, never()).updateById(any(RuleDefinition.class));
    }

    @Test
    void activeStrategyOnlyBlocksItsOwnReferencedRules() {
        RuleStrategyService realStrategies = new RuleStrategyService(null, null, null, null, null) {
            @Override
            public RuleStrategyDetailDto getActiveStrategy() {
                return activeStrategy("R_TREND");
            }
        };

        assertThatThrownBy(() -> realStrategies.assertCanDeactivateRule("R_TREND"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("S_ACTIVE")
                .hasMessageContaining("R_TREND");
        realStrategies.assertCanDeactivateRule("R_OTHER");
    }

    private RuleDefinition existing(String code) {
        return RuleDefinition.builder().id(1L).ruleCode(code).status("active")
                .ruleFormat("drools").enabled(true).ruleContent(validDrl()).build();
    }

    private RuleDefinitionUpsertDto updateRequest(String status) {
        RuleDefinitionUpsertDto request = new RuleDefinitionUpsertDto();
        request.setRuleCode("R_TREND");
        request.setRuleName("趋势确认");
        request.setStatus(status);
        request.setRuleFormat("drools");
        request.setRuleContent(validDrl());
        request.setVersion("v5");
        return request;
    }

    private String validDrl() {
        return "rule \"R_TREND\" when then end";
    }

    private RuleStrategyDetailDto activeStrategy(String ruleCode) {
        RuleGroupMemberDto member = new RuleGroupMemberDto();
        member.setRuleCode(ruleCode);
        RuleGroupDetailDto group = new RuleGroupDetailDto();
        group.setGroupCode("G_TREND");
        group.setMembers(List.of(member));
        RuleStrategyGroupDto selected = new RuleStrategyGroupDto();
        selected.setGroupCode("G_TREND");
        selected.setGroup(group);
        RuleStrategyDetailDto strategy = new RuleStrategyDetailDto();
        strategy.setStrategyCode("S_ACTIVE");
        strategy.setGroups(List.of(selected));
        return strategy;
    }
}
