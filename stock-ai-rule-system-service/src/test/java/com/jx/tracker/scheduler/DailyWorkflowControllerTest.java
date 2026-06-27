package com.jx.tracker.scheduler;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.controller.DailyWorkflowController;
import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;
import com.jx.tracker.domain.vo.DailyWorkflowRunResultVo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DailyWorkflowControllerTest {

    @Test
    void triggerDailyWorkflowReturnsAjaxResultContract() {
        DailyWorkflowOrchestrator orchestrator = new DailyWorkflowOrchestrator(List.of());
        DailyWorkflowController controller = new DailyWorkflowController(orchestrator);

        AjaxResult response = controller.triggerDailyWorkflow(new DailyWorkflowTriggerDto());

        assertThat(response.get(AjaxResult.CODE_TAG)).isEqualTo(200);
        assertThat(response.get(AjaxResult.DATA_TAG)).isInstanceOf(DailyWorkflowRunResultVo.class);
        DailyWorkflowRunResultVo result = (DailyWorkflowRunResultVo) response.get(AjaxResult.DATA_TAG);
        assertThat(result.getDryRun()).isTrue();
        assertThat(result.getRiskDisclaimer()).contains("辅助决策");
    }
}
