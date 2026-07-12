package com.jx.tracker.controller;

import com.jx.tracker.common.GlobalExceptionHandler;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.service.StockConsoleQueryService;
import com.jx.tracker.service.StockDashboardQueryService;
import com.jx.tracker.service.StockWatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StockConsoleControllerTest {

    @Test
    void exposesPagedCandidatesAndBatchAddRoutes() throws Exception {
        when(watchlistService.searchCandidates("focus", "A股", "茅台", 1, 20))
                .thenReturn(PageResult.getDataTable(List.of(new StockConsoleVo.WatchlistCandidate(
                        "600519.SH", "贵州茅台", "A股", "SH", "白酒", false)), 1L));
        when(watchlistService.addStocks(any(), any())).thenReturn(
                new StockConsoleVo.WatchlistBatchMutationResult(
                        "focus", List.of("600519.SH"), List.of(), List.of()));

        mockMvc.perform(get("/api/watchlists/focus/stock-candidates")
                        .param("market", "A股")
                        .param("keyword", "茅台"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].symbol").value("600519.SH"));
        mockMvc.perform(post("/api/watchlists/focus/stocks/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbols\":[\"600519.SH\"],\"groupName\":\"核心\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.addedSymbols[0]").value("600519.SH"));

        verify(watchlistService).addStocks(
                "focus",
                new StockConsoleVo.WatchlistBatchMutationRequest(List.of("600519.SH"), "核心"));
    }

    private StockDashboardQueryService dashboardQueryService;
    private StockWatchlistService watchlistService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        StockConsoleQueryService consoleQueryService = mock(StockConsoleQueryService.class);
        dashboardQueryService = mock(StockDashboardQueryService.class);
        watchlistService = mock(StockWatchlistService.class);
        StockConsoleController controller = new StockConsoleController(
                consoleQueryService,
                dashboardQueryService,
                watchlistService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void bindsCompleteDashboardQueryToDedicatedService() throws Exception {
        when(dashboardQueryService.dashboard(any())).thenReturn(emptyDashboard());

        mockMvc.perform(get("/api/signals/dashboard")
                        .param("date", "2026-07-10")
                        .param("market", "CN")
                        .param("poolCode", "focus")
                        .param("symbol", "600519")
                        .param("signal", "bullish")
                        .param("industry", "白酒")
                        .param("confidenceMin", "0.60")
                        .param("confidenceMax", "0.90")
                        .param("pageNum", "2")
                        .param("pageSize", "50")
                        .param("sortField", "updatedAt")
                        .param("sortOrder", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<StockConsoleVo.SignalDashboardQuery> captor =
                ArgumentCaptor.forClass(StockConsoleVo.SignalDashboardQuery.class);
        verify(dashboardQueryService).dashboard(captor.capture());
        StockConsoleVo.SignalDashboardQuery query = captor.getValue();
        assertThat(query.date()).isEqualTo(LocalDate.of(2026, 7, 10));
        assertThat(query.market()).isEqualTo("A股");
        assertThat(query.poolCode()).isEqualTo("focus");
        assertThat(query.symbol()).isEqualTo("600519");
        assertThat(query.signal()).isEqualTo("bullish");
        assertThat(query.industry()).isEqualTo("白酒");
        assertThat(query.confidenceMin()).isEqualByComparingTo("0.60");
        assertThat(query.confidenceMax()).isEqualByComparingTo("0.90");
        assertThat(query.pageNum()).isEqualTo(2);
        assertThat(query.pageSize()).isEqualTo(50);
        assertThat(query.sortField()).isEqualTo("updatedAt");
        assertThat(query.sortOrder()).isEqualTo("asc");
    }

    @Test
    void exposesWatchlistCrudAndMemberRoutes() throws Exception {
        StockConsoleVo.WatchlistPool pool = new StockConsoleVo.WatchlistPool(
                "focus", "重点关注", "A股", 0, List.of()
        );
        when(watchlistService.create(any())).thenReturn(pool);
        when(watchlistService.update(any(), any())).thenReturn(pool);
        when(watchlistService.addStock(any(), any())).thenReturn(pool);
        when(watchlistService.removeStock(any(), any())).thenReturn(pool);

        String poolJson = "{\"poolName\":\"重点关注\",\"market\":\"A股\"}";
        mockMvc.perform(post("/api/watchlists").contentType(MediaType.APPLICATION_JSON).content(poolJson))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.poolId").value("focus"));
        mockMvc.perform(put("/api/watchlists/focus").contentType(MediaType.APPLICATION_JSON).content(poolJson))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/watchlists/focus/stocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"600519.SH\",\"groupName\":\"核心\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/watchlists/focus/stocks/600519.SH"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/watchlists/focus"))
                .andExpect(status().isOk());

        verify(watchlistService).create(new StockConsoleVo.WatchlistMutationRequest("重点关注", "A股"));
        verify(watchlistService).update(
                "focus", new StockConsoleVo.WatchlistMutationRequest("重点关注", "A股")
        );
        verify(watchlistService).addStock(
                "focus", new StockConsoleVo.WatchlistStockMutationRequest("600519.SH", "核心")
        );
        verify(watchlistService).removeStock("focus", "600519.SH");
        verify(watchlistService).delete("focus");
    }

    @Test
    void rejectsBlankWatchlistAndStockBodies() throws Exception {
        mockMvc.perform(post("/api/watchlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"poolName\":\"\",\"market\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/watchlists/focus/stocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void mapsCodedWatchlistBusinessErrorToHttpStatus() throws Exception {
        doThrow(new ServiceException("系统股票池不可删除", 400))
                .when(watchlistService).delete("my-follow");

        mockMvc.perform(delete("/api/watchlists/my-follow"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("系统股票池不可删除"));
    }

    private StockConsoleVo.SignalDashboardOverview emptyDashboard() {
        return new StockConsoleVo.SignalDashboardOverview(
                LocalDate.of(2026, 7, 10),
                "信号仅用于辅助决策，不构成投资建议。",
                List.of(new StockConsoleVo.MetricCard("产生信号", BigDecimal.ZERO, "条", null, "cyan")),
                List.of(),
                new StockConsoleVo.MarketContext(false, "000300.SH", null, null, "unavailable",
                        List.of(), List.of(),
                        new StockConsoleVo.Sentiment("信号情绪（7 日）·暂无数据", null, "unavailable"),
                        new StockConsoleVo.RiskOverview(0, null, "unavailable", "unavailable", "暂无数据")),
                0, 1, 20, List.of(), null
        );
    }
}
