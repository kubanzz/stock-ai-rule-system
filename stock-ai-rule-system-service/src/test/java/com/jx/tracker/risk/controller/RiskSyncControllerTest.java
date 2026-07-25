package com.jx.tracker.risk.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jx.tracker.common.GlobalExceptionHandler;
import com.jx.tracker.risk.sync.RiskSyncJob;
import com.jx.tracker.risk.sync.RiskSyncJobService;
import com.jx.tracker.risk.sync.RiskSyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RiskSyncControllerTest {

    private RiskSyncJobService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(RiskSyncJobService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(new RiskSyncController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void exposesMarketStockJobAndStatusEndpoints() throws Exception {
        RiskSyncJob market = queued("market-job", "market:CN-A");
        RiskSyncJob stock = queued("stock-job", "stock:600519.SH");
        when(service.startMarketSync()).thenReturn(market);
        when(service.startStockSync("600519")).thenReturn(stock);
        when(service.get("market-job")).thenReturn(Optional.of(market));
        when(service.status()).thenReturn(new RiskSyncStatus(market, List.of(market, stock)));

        mockMvc.perform(post("/api/risks/sync/market"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.jobId").value("market-job"));
        mockMvc.perform(post("/api/risks/sync/stocks/600519"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopeKey").value("stock:600519.SH"));
        mockMvc.perform(get("/api/risks/sync/jobs/market-job"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("queued"));
        mockMvc.perform(get("/api/risks/sync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestMarketJob.jobId").value("market-job"))
                .andExpect(jsonPath("$.data.activeJobs.length()").value(2));
    }

    @Test
    void returnsNotFoundForUnknownJob() throws Exception {
        when(service.get("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/risks/sync/jobs/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    private RiskSyncJob queued(String jobId, String scopeKey) {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 25, 10, 0);
        return new RiskSyncJob(
                jobId, scopeKey, "queued", "resolving_trade_date", 0,
                null, 0, 0, 0, 0, 0, null,
                createdAt, null, null
        );
    }
}
