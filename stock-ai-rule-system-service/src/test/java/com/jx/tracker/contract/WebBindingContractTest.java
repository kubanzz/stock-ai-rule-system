package com.jx.tracker.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jx.tracker.domain.dto.BacktestRequestDto;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebBindingContractTest {

    private static final List<String> CONTROLLER_CLASSES = List.of(
            "com.jx.tracker.signal.controller.StockSignalController",
            "com.jx.tracker.market.data.controller.MarketDataController",
            "com.jx.tracker.controller.AiReviewController",
            "com.jx.tracker.controller.UserController",
            "com.jx.tracker.controller.CandidateRuleController",
            "com.jx.tracker.rule.controller.RuleDefinitionController"
    );

    @Test
    void requestParamsAndPathVariablesDeclareExplicitNames() throws Exception {
        for (String className : CONTROLLER_CLASSES) {
            Class<?> controllerClass = Class.forName(className);
            assertThat(controllerClass.isAnnotationPresent(RestController.class)).isTrue();
            for (Method method : controllerClass.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
                    if (requestParam != null) {
                        assertThat(namedValue(requestParam.value(), requestParam.name()))
                                .as("%s#%s @RequestParam %s", className, method.getName(), parameter)
                                .isNotBlank();
                    }
                    PathVariable pathVariable = parameter.getAnnotation(PathVariable.class);
                    if (pathVariable != null) {
                        assertThat(namedValue(pathVariable.value(), pathVariable.name()))
                                .as("%s#%s @PathVariable %s", className, method.getName(), parameter)
                                .isNotBlank();
                    }
                }
            }
        }
    }

    @Test
    void backtestRequestAcceptsFrontendCamelCasePayload() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        BacktestRequestDto dto = mapper.readValue("""
                {
                  "objectType": "rule",
                  "objectCode": "R_TREND_BREAKOUT_001",
                  "startDate": "2023-01-01",
                  "endDate": "2026-06-20",
                  "holdingPeriod": 5
                }
                """, BacktestRequestDto.class);

        assertThat(dto.getObjectType()).isEqualTo("rule");
        assertThat(dto.getObjectCode()).isEqualTo("R_TREND_BREAKOUT_001");
        assertThat(dto.getStartDate()).isEqualTo(LocalDate.of(2023, 1, 1));
        assertThat(dto.getEndDate()).isEqualTo(LocalDate.of(2026, 6, 20));
        assertThat(dto.getHoldingPeriod()).isEqualTo(5);
    }

    private String namedValue(String value, String name) {
        return value == null || value.isBlank() ? name : value;
    }
}
