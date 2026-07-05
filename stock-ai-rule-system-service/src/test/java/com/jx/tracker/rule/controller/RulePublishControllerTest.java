package com.jx.tracker.rule.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.constant.HttpStatus;
import com.jx.tracker.domain.dto.RuleOperationLogDto;
import com.jx.tracker.domain.dto.RulePublishRequestDto;
import com.jx.tracker.domain.dto.RulePublishResultDto;
import com.jx.tracker.domain.dto.RuleVersionDto;
import com.jx.tracker.rule.service.RulePublishService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;

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
    void controllerMappingsStayRestfulAndExplicit() throws NoSuchMethodException {
        RequestMapping classMapping = RulePublishController.class.getAnnotation(RequestMapping.class);
        assertThat(classMapping.value()).containsExactly("/api/rules");

        PostMapping publishMapping = RulePublishController.class
                .getDeclaredMethod("publishCandidateRule", String.class, RulePublishRequestDto.class)
                .getAnnotation(PostMapping.class);
        assertThat(publishMapping.value()).containsExactly("/candidates/{candidateCode}/publish");

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
