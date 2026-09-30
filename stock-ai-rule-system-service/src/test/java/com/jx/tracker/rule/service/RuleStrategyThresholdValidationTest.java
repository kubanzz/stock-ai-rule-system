package com.jx.tracker.rule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.dto.RuleGroupDetailDto;
import com.jx.tracker.domain.dto.RuleGroupMemberDto;
import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.domain.dto.RuleStrategyGroupDto;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.RuleStrategyMapper;
import com.jx.tracker.mapper.RuleStrategyVersionMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleStrategyThresholdValidationTest {

    @Test
    void rejectsZeroForEachConfiguredThreshold() {
        RuleGroupService groups = mock(RuleGroupService.class);
        when(groups.getGroup("G_TEST")).thenReturn(group());
        RuleDefinitionMapper definitions = mock(RuleDefinitionMapper.class);
        when(definitions.selectOne(any())).thenReturn(RuleDefinition.builder()
                .ruleCode("R_TEST").ruleFormat(RuleFormat.DROOLS.getCode())
                .status(RuleLifecycleStatus.ACTIVE.getCode()).enabled(true).build());
        RuleStrategyService service = new RuleStrategyService(mock(RuleStrategyMapper.class),
                mock(RuleStrategyVersionMapper.class), groups, definitions, new ObjectMapper());

        RuleStrategyDetailDto zeroBullish = strategy();
        zeroBullish.setBullishThreshold(BigDecimal.ZERO);
        assertThatThrownBy(() -> service.create(zeroBullish))
                .isInstanceOf(ServiceException.class).hasMessageContaining("看涨阈值须大于 0");

        RuleStrategyDetailDto zeroBearish = strategy();
        zeroBearish.setBearishThreshold(BigDecimal.ZERO);
        assertThatThrownBy(() -> service.create(zeroBearish))
                .isInstanceOf(ServiceException.class).hasMessageContaining("看跌阈值须大于 0");

        RuleStrategyDetailDto zeroRisk = strategy();
        zeroRisk.setRiskThreshold(BigDecimal.ZERO);
        assertThatThrownBy(() -> service.create(zeroRisk))
                .isInstanceOf(ServiceException.class).hasMessageContaining("高风险阈值须大于 0");
    }

    private RuleStrategyDetailDto strategy() {
        RuleStrategyGroupDto selected = new RuleStrategyGroupDto();
        selected.setGroupCode("G_TEST");
        selected.setWeight(BigDecimal.ONE);
        RuleStrategyDetailDto strategy = new RuleStrategyDetailDto();
        strategy.setStrategyCode("S_TEST");
        strategy.setStrategyName("测试方案");
        strategy.setStatus("active");
        strategy.setGroups(List.of(selected));
        return strategy;
    }

    private RuleGroupDetailDto group() {
        RuleGroupMemberDto member = new RuleGroupMemberDto();
        member.setRuleCode("R_TEST");
        RuleGroupDetailDto group = new RuleGroupDetailDto();
        group.setGroupCode("G_TEST");
        group.setVersion("v1");
        group.setStatus("active");
        group.setAggregation("OR");
        group.setMinMatchedRules(1);
        group.setMembers(List.of(member));
        return group;
    }
}
