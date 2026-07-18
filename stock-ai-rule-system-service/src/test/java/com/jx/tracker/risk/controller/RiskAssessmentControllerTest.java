package com.jx.tracker.risk.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jx.tracker.common.GlobalExceptionHandler;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.risk.model.RiskDecisionSupportNotice;
import com.jx.tracker.risk.query.RiskAssessmentQueryService;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskEvidence;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskLevelCount;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectDetail;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectListItem;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectRef;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskOverview;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskSnapshot;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskTrendPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RiskAssessmentControllerTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 18);

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
    void matchesFrozenFrontendJsonContractAndBindsEveryQueryParameter() throws Exception {
        RiskSnapshot snapshot = snapshot("5-20d", "warning");
        RiskObjectListItem item = new RiskObjectListItem("贵州茅台", snapshot.object(), snapshot);
        RiskOverview overview = new RiskOverview(
                "5-20d", TRADE_DATE,
                List.of(new RiskLevelCount("warning", 1L)), null, List.of(item),
                RiskDecisionSupportNotice.TEXT
        );
        RiskObjectDetail detail = new RiskObjectDetail(
                item.name(), item.object(), item.snapshot(), List.of("credit_event"),
                List.of(new RiskObjectRef("sector", "BK0475")), List.of(snapshot)
        );
        RiskTrendPoint trendPoint = new RiskTrendPoint(
                TRADE_DATE, new BigDecimal("70"), new BigDecimal("65"), new BigDecimal("55"),
                new BigDecimal("60"), new BigDecimal("45"), new BigDecimal("75"),
                "warning", new BigDecimal("0.90")
        );
        when(queryService.overview("5-20d", TRADE_DATE)).thenReturn(overview);
        when(queryService.listObjects(
                "stock", "warning", "5-20d", TRADE_DATE, "茅台", 2, 10
        )).thenReturn(PageResult.getDataTable(List.of(item), 1L));
        when(queryService.objectDetail("stock", "600519.SH", "5-20d", TRADE_DATE))
                .thenReturn(detail);
        when(queryService.trend(
                "stock", "600519.SH", "5-20d", LocalDate.of(2026, 7, 1), TRADE_DATE
        )).thenReturn(List.of(trendPoint));

        mockMvc.perform(get("/api/risks/overview")
                        .param("horizon", "5-20d")
                        .param("tradeDate", "2026-07-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.horizon").value("5-20d"))
                .andExpect(jsonPath("$.data.levelCounts[0].level").value("warning"))
                .andExpect(jsonPath("$.data.marketSnapshot").doesNotExist())
                .andExpect(jsonPath("$.data.highRiskObjects[0].object.objectType").value("stock"))
                .andExpect(jsonPath("$.data.riskDisclaimer").value(RiskDecisionSupportNotice.TEXT))
                .andExpect(jsonPath("$.data.riskNotice").doesNotExist());

        mockMvc.perform(get("/api/risks/objects")
                        .param("objectType", "stock")
                        .param("level", "warning")
                        .param("horizon", "5-20d")
                        .param("tradeDate", "2026-07-18")
                        .param("keyword", "茅台")
                        .param("pageNum", "2")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.rows[0].name").value("贵州茅台"))
                .andExpect(jsonPath("$.rows[0].object.objectId").value("600519.SH"))
                .andExpect(jsonPath("$.rows[0].snapshot.level").value("warning"))
                .andExpect(jsonPath("$.rows[0].periods").doesNotExist());

        mockMvc.perform(get("/api/risks/objects/stock/600519.SH")
                        .param("horizon", "5-20d")
                        .param("tradeDate", "2026-07-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeTriggers[0]").value("credit_event"))
                .andExpect(jsonPath("$.data.parentObjects[0].objectType").value("sector"))
                .andExpect(jsonPath("$.data.snapshot.horizon").value("5-20d"))
                .andExpect(jsonPath("$.data.snapshots[0].evidence[0].dimension").value("T"))
                .andExpect(jsonPath("$.data.snapshots[0].evidence[0].score").value(80))
                .andExpect(jsonPath("$.data.snapshots[0].evidence[0].dimensionCode").doesNotExist())
                .andExpect(jsonPath("$.data.snapshots[0].evidence[0].indicatorScore").doesNotExist());

        mockMvc.perform(get("/api/risks/objects/stock/600519.SH/trend")
                        .param("horizon", "5-20d")
                        .param("startDate", "2026-07-01")
                        .param("endDate", "2026-07-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].tradeDate").value("2026-07-18"))
                .andExpect(jsonPath("$.data[0].horizon").doesNotExist())
                .andExpect(jsonPath("$.data.points").doesNotExist());

        verify(queryService).overview("5-20d", TRADE_DATE);
        verify(queryService).listObjects("stock", "warning", "5-20d", TRADE_DATE, "茅台", 2, 10);
        verify(queryService).objectDetail("stock", "600519.SH", "5-20d", TRADE_DATE);
        verify(queryService).trend(
                "stock", "600519.SH", "5-20d", LocalDate.of(2026, 7, 1), TRADE_DATE
        );
    }

    @Test
    void rejectsPageSizeOutsideOneToOneHundred() throws Exception {
        mockMvc.perform(get("/api/risks/objects").param("pageSize", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(get("/api/risks/objects").param("pageSize", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void rejectsInvertedTrendDateRange() throws Exception {
        mockMvc.perform(get("/api/risks/objects/stock/600519.SH/trend")
                        .param("startDate", "2026-07-18")
                        .param("endDate", "2026-07-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    private RiskSnapshot snapshot(String horizon, String level) {
        RiskObjectRef object = new RiskObjectRef("stock", "600519.SH");
        RiskEvidence evidence = new RiskEvidence(
                "T", "credit_event", new BigDecimal("12.5"), new BigDecimal("80"),
                LocalDateTime.of(2026, 7, 18, 15, 0), LocalDateTime.of(2026, 7, 18, 15, 30),
                "test", "available", Map.of("reason", "credit")
        );
        return new RiskSnapshot(
                object, horizon, TRADE_DATE,
                new BigDecimal("70"), new BigDecimal("65"), new BigDecimal("55"),
                new BigDecimal("60"), new BigDecimal("45"), new BigDecimal("1.05"),
                new BigDecimal("75"), level, "repricing", new BigDecimal("0.90"),
                new BigDecimal("0.88"), List.of(evidence), "risk-v1",
                LocalDateTime.of(2026, 7, 18, 16, 0)
        );
    }
}
