package com.jx.tracker.rule.controller;

import com.jx.tracker.rule.service.RuleGroupService;
import com.jx.tracker.rule.service.RuleStrategyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuleCompositionControllerTest {

    @Test
    void groupRoutesBindQueryAndPathParametersWithoutCompilerParameterNames() throws Exception {
        RuleGroupService service = mock(RuleGroupService.class);
        when(service.listGroups(null)).thenReturn(List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RuleGroupController(service)).build();

        mvc.perform(get("/api/rule-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/rule-groups/G1")).andExpect(status().isOk());
        mvc.perform(get("/api/rule-groups/G1/versions")).andExpect(status().isOk());
        mvc.perform(get("/api/rule-groups/G1/versions/v1")).andExpect(status().isOk());
        mvc.perform(put("/api/rule-groups/G1").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/rule-groups/G1/copy").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/rule-groups/G1/status").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void strategyRoutesBindQueryAndPathParametersWithoutCompilerParameterNames() throws Exception {
        RuleStrategyService service = mock(RuleStrategyService.class);
        when(service.listStrategies(null)).thenReturn(List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RuleStrategyController(service)).build();

        mvc.perform(get("/api/rule-strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/rule-strategies/active")).andExpect(status().isOk());
        mvc.perform(get("/api/rule-strategies/S1")).andExpect(status().isOk());
        mvc.perform(get("/api/rule-strategies/S1/versions")).andExpect(status().isOk());
        mvc.perform(get("/api/rule-strategies/S1/versions/v1")).andExpect(status().isOk());
        mvc.perform(put("/api/rule-strategies/S1").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/rule-strategies/S1/copy").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/rule-strategies/S1/status").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
    }
}
