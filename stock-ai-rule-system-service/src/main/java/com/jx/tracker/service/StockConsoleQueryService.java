package com.jx.tracker.service;

import com.jx.tracker.domain.vo.StockConsoleVo;

import java.time.LocalDate;
import java.util.List;

public interface StockConsoleQueryService {

    StockConsoleVo.SignalDashboardOverview dashboard(LocalDate date, String market, String poolCode);

    List<StockConsoleVo.WatchlistPool> watchlists(String market);

    StockConsoleVo.WatchlistPool addWatchlistStock(String poolId, StockConsoleVo.WatchlistStockMutationRequest request);

    StockConsoleVo.WatchlistPool removeWatchlistStock(String poolId, String symbol);

    StockConsoleVo.StockResearchDetail research(String symbol, LocalDate date);

    StockConsoleVo.RuleGovernanceOverview ruleGovernance(String ruleType, String status, String source);

    StockConsoleVo.RuleGovernanceDetail ruleGovernanceDetail(String ruleCode);

    StockConsoleVo.BacktestReportOverview backtestReports(String objectCode, String market);

    StockConsoleVo.BacktestReportDetail backtestReport(String reportId);

    List<StockConsoleVo.BacktestFailureSample> backtestFailureSamples(String reportId);

    StockConsoleVo.AiReviewOverview aiReviewSummary(LocalDate date);

    List<StockConsoleVo.MisjudgementSample> aiMisjudgements(LocalDate date, String reasonCategory);

    StockConsoleVo.CandidateRuleSuggestion createCandidateFromMisjudgement(String sampleId);

    StockConsoleVo.RunCenterOverview runCenterOverview(LocalDate date);
}
