package com.jx.tracker.risk.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jx.tracker.common.GlobalExceptionHandler;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.risk.model.RiskDecisionSupportNotice;
import com.jx.tracker.risk.query.RiskAssessmentQueryService;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.Overview;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectSummary;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskTrend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RiskAssessmentControllerTest {

    private RiskAssessmentQueryService queryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(RiskAssessmentQueryService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(new RiskAssessmentController(queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void exposesOverviewObjectsDetailAndTrendWithCamelCaseSuccessContracts() throws Exception {
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        counts.put("normal", 1L);
        counts.put("critical", 1L);
        Overview overview = new Overview(
                LocalDate.of(2026, 7, 18), counts, 1L, List.of(), RiskDecisionSupportNotice.TEXT
        );
        RiskObjectSummary summary = new RiskObjectSummary(
                "stock", "600519.SH", LocalDate.of(2026, 7, 18), "critical", null,
                List.of(), RiskDecisionSupportNotice.TEXT
        );
        when(queryService.overview(LocalDate.of(2026, 7, 18))).thenReturn(overview);
        when(queryService.listObjects("stock", "critical", 1, 20))
                .thenReturn(PageResult.getDataTable(List.of(summary), 1L));
        when(queryService.objectDetail("stock", "600519.SH")).thenReturn(null);
        when(queryService.trend(
                "stock", "600519.SH", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 18)
        )).thenReturn(new RiskTrend(
                "stock", "600519.SH", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 18),
                List.of(), RiskDecisionSupportNotice.TEXT
        ));

        mockMvc.perform(get("/api/risks/overview").param("tradeDate", "2026-07-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.highRiskObjectCount").value(1))
                .andExpect(jsonPath("$.data.riskNotice").value(RiskDecisionSupportNotice.TEXT))
                .andExpect(jsonPath("$.data.riskProbability").doesNotExist());

        mockMvc.perform(get("/api/risks/objects")
                        .param("objectType", "stock")
                        .param("riskLevel", "critical"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].objectType").value("stock"))
                .andExpect(jsonPath("$.rows[0].objectId").value("600519.SH"));

        mockMvc.perform(get("/api/risks/objects/stock/600519.SH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/risks/objects/stock/600519.SH/trend")
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.startDate").value("2026-07-01"));

        verify(queryService).objectDetail("stock", "600519.SH");
    }

    @Test
    void rejectsPageSizeOutsideOneToOneHundred() throws Exception {
        mockMvc.perform(get("/api/risks/objects").param("pageSize", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(get("/api/risks/objects").param("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void rejectsInvertedTrendDateRange() throws Exception {
        mockMvc.perform(get("/api/risks/objects/stock/600519.SH/trend")
                        .param("startDate", "2026-07-18")
                        .param("endDate", "2026-07-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
