package com.jx.tracker.rule.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.constant.HttpStatus;
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
import com.jx.tracker.domain.enums.RuleVersionApprovalStatus;
import com.jx.tracker.mapper.CandidateRuleMapper;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.RuleOperationLogMapper;
import com.jx.tracker.mapper.RuleVersionMapper;
import com.jx.tracker.rule.service.RulePublishService;
import com.jx.tracker.rule.service.RulePublishServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RulePublishControllerTest {

    @Test
    void publishCandidateEndpointReturnsVersionAndAuditInformation() {
        CapturingRulePublishService service = new CapturingRulePublishService();
        RulePublishController controller = new RulePublishController(service);
        RulePublishRequestDto request = new RulePublishRequestDto();
        request.setOperator("reviewer");
        request.setReason("人工审核通过");

        AjaxResult response = controller.publishCandidateRule("CR_20260706_0001", request);

        assertThat(response.get(AjaxResult.CODE_TAG)).isEqualTo(HttpStatus.SUCCESS);
        assertThat(service.candidateCode).isEqualTo("CR_20260706_0001");
        assertThat(service.operator).isEqualTo("reviewer");
        assertThat(service.reason).isEqualTo("人工审核通过");
        assertThat(response.get(AjaxResult.DATA_TAG)).isInstanceOf(RulePublishResultDto.class);
        RulePublishResultDto data = (RulePublishResultDto) response.get(AjaxResult.DATA_TAG);
        assertThat(data.getVersion().getVersionNo()).isEqualTo("v2");
        assertThat(data.getOperationLog().getOperation()).isEqualTo("publish");
    }

    @Test
    void publishEndpointReturnsPublishedCandidateStatusAndStructuredAuditFromRealService() {
        CandidateRuleMapper candidateRuleMapper = mock(CandidateRuleMapper.class);
        RuleDefinitionMapper ruleDefinitionMapper = mock(RuleDefinitionMapper.class);
        RuleVersionMapper ruleVersionMapper = mock(RuleVersionMapper.class);
        RuleOperationLogMapper operationLogMapper = mock(RuleOperationLogMapper.class);
        RulePublishController controller = new RulePublishController(new RulePublishServiceImpl(
                candidateRuleMapper,
                ruleDefinitionMapper,
                ruleVersionMapper,
                operationLogMapper
        ));
        when(candidateRuleMapper.selectOne(any())).thenReturn(CandidateRule.builder()
                .id(10L)
                .candidateCode("CR_20260706_0001")
                .targetRuleCode("R_TREND_BREAKOUT_001")
                .proposedContent("new-json")
                .reason("减少弱势市场假突破")
                .status(RuleLifecycleStatus.APPROVED.getCode())
                .backtestStatus(BacktestStatus.SUCCESS.getCode())
                .approvalStatus(RuleVersionApprovalStatus.APPROVED.getCode())
                .build());
        when(ruleDefinitionMapper.selectOne(any())).thenReturn(RuleDefinition.builder()
                .id(100L)
                .ruleCode("R_TREND_BREAKOUT_001")
                .ruleName("趋势突破")
                .ruleType("signal")
                .ruleContent("old-json")
                .ruleFormat("json")
                .version("v1")
                .status(RuleLifecycleStatus.APPROVED.getCode())
                .currentVersionId(90L)
                .currentVersionNo("v1")
                .enabled(false)
                .priority(10)
                .build());
        when(ruleVersionMapper.selectList(any())).thenReturn(List.of());
        when(ruleVersionMapper.insert(any(RuleVersion.class))).thenAnswer(invocation -> {
            RuleVersion version = invocation.getArgument(0);
            version.setId(200L);
            return 1;
        });
        when(ruleDefinitionMapper.updateById(any(RuleDefinition.class))).thenReturn(1);
        when(candidateRuleMapper.updateById(any(CandidateRule.class))).thenReturn(1);
        when(operationLogMapper.insert(any(RuleOperationLog.class))).thenAnswer(invocation -> {
            RuleOperationLog log = invocation.getArgument(0);
            log.setId(300L);
            return 1;
        });
        RulePublishRequestDto request = new RulePublishRequestDto();
        request.setOperator("reviewer");
        request.setReason("人工审核通过");

        AjaxResult response = controller.publishCandidateRule("CR_20260706_0001", request);

        RulePublishResultDto data = (RulePublishResultDto) response.get(AjaxResult.DATA_TAG);
        assertThat(data.getStatus()).isEqualTo(CandidateRuleStatus.PUBLISHED.getCode());
        assertThat(data.getOperationLog().getAfterStatus()).isEqualTo(CandidateRuleStatus.PUBLISHED.getCode());
        assertThat(data.getOperationLog().getReason())
                .contains("\"ruleCode\":\"R_TREND_BREAKOUT_001\"")
                .contains("\"versionId\":200")
                .contains("\"versionNo\":\"v2\"")
                .contains("\"candidateCode\":\"CR_20260706_0001\"")
                .contains("\"originalReason\":\"人工审核通过\"");
    }

    @Test
    void rollbackEndpointReturnsVersionAndAuditInformation() {
        CapturingRulePublishService service = new CapturingRulePublishService();
        RulePublishController controller = new RulePublishController(service);
        RulePublishRequestDto request = new RulePublishRequestDto();
        request.setOperator("reviewer");
        request.setReason("回滚到稳定版本");

        AjaxResult response = controller.rollbackRuleVersion("R_TREND_BREAKOUT_001", "90", request);

        assertThat(response.get(AjaxResult.CODE_TAG)).isEqualTo(HttpStatus.SUCCESS);
        assertThat(service.ruleCode).isEqualTo("R_TREND_BREAKOUT_001");
        assertThat(service.versionId).isEqualTo("90");
        assertThat(service.operator).isEqualTo("reviewer");
        assertThat(service.reason).isEqualTo("回滚到稳定版本");
        RulePublishResultDto data = (RulePublishResultDto) response.get(AjaxResult.DATA_TAG);
        assertThat(data.getVersion().getVersionNo()).isEqualTo("v1");
        assertThat(data.getOperationLog().getOperation()).isEqualTo("rollback");
    }

    @Test
    void listRuleVersionsEndpointReturnsVersionRows() {
        CapturingRulePublishService service = new CapturingRulePublishService();
        RulePublishController controller = new RulePublishController(service);

        AjaxResult response = controller.listRuleVersions("R_TREND_BREAKOUT_001");

        assertThat(response.get(AjaxResult.CODE_TAG)).isEqualTo(HttpStatus.SUCCESS);
        assertThat(service.ruleCode).isEqualTo("R_TREND_BREAKOUT_001");
        assertThat(response.get(AjaxResult.DATA_TAG)).asList().hasSize(2);
    }

    @Test
    void controllerMappingsStayRestfulAndExplicit() throws NoSuchMethodException {
        RequestMapping classMapping = RulePublishController.class.getAnnotation(RequestMapping.class);
        assertThat(classMapping.value()).containsExactly("/api/rules");

        PostMapping publishMapping = RulePublishController.class
                .getDeclaredMethod("publishCandidateRule", String.class, RulePublishRequestDto.class)
                .getAnnotation(PostMapping.class);
        assertThat(publishMapping.value()).containsExactly("/candidates/{candidateCode}/publish");

        GetMapping listVersionsMapping = RulePublishController.class
                .getDeclaredMethod("listRuleVersions", String.class)
                .getAnnotation(GetMapping.class);
        assertThat(listVersionsMapping.value()).containsExactly("/{ruleCode}/versions");

        PostMapping rollbackMapping = RulePublishController.class
                .getDeclaredMethod("rollbackRuleVersion", String.class, String.class, RulePublishRequestDto.class)
                .getAnnotation(PostMapping.class);
        assertThat(rollbackMapping.value()).containsExactly("/{ruleCode}/versions/{versionId}/rollback");
    }

    private static class CapturingRulePublishService implements RulePublishService {

        private String candidateCode;
        private String ruleCode;
        private String versionId;
        private String operator;
        private String reason;

        @Override
        public RulePublishResultDto publishCandidateRule(String candidateCode, String operator, String reason) {
            this.candidateCode = candidateCode;
            this.operator = operator;
            this.reason = reason;
            return result("R_TREND_BREAKOUT_001", candidateCode, "v2", "publish");
        }

        @Override
        public RulePublishResultDto rollbackRuleVersion(String ruleCode, String versionId, String operator, String reason) {
            this.ruleCode = ruleCode;
            this.versionId = versionId;
            this.operator = operator;
            this.reason = reason;
            return result(ruleCode, null, "v1", "rollback");
        }

        @Override
        public List<RuleVersionDto> listRuleVersions(String ruleCode) {
            this.ruleCode = ruleCode;
            RuleVersionDto published = new RuleVersionDto();
            published.setVersionNo("v2");
            published.setRuleContent("new-json");
            RuleVersionDto previous = new RuleVersionDto();
            previous.setVersionNo("v1");
            previous.setRuleContent("old-json");
            return List.of(published, previous);
        }

        private RulePublishResultDto result(String ruleCode, String candidateCode, String versionNo, String operation) {
            RuleVersionDto version = new RuleVersionDto();
            version.setRuleId(100L);
            version.setVersionNo(versionNo);
            RuleOperationLogDto log = new RuleOperationLogDto();
            log.setOperation(operation);

            RulePublishResultDto result = new RulePublishResultDto();
            result.setRuleCode(ruleCode);
            result.setCandidateCode(candidateCode);
            result.setVersion(version);
            result.setOperationLog(log);
            return result;
        }
    }
}
