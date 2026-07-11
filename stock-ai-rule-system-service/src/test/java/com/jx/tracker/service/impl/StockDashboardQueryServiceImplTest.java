package com.jx.tracker.service.impl;

import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.entity.StockActualResult;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.entity.StockWatchlist;
import com.jx.tracker.domain.entity.StockWatchlistItem;
import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.StockActualResultMapper;
import com.jx.tracker.mapper.StockBaseMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.mapper.StockWatchlistItemMapper;
import com.jx.tracker.mapper.StockWatchlistMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockDashboardQueryServiceImplTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 10);

    @Mock
    private StockBaseMapper stockBaseMapper;
    @Mock
    private StockSignalDailyMapper stockSignalDailyMapper;
    @Mock
    private StockDailyQuoteMapper stockDailyQuoteMapper;
    @Mock
    private StockActualResultMapper stockActualResultMapper;
    @Mock
    private StockWatchlistMapper stockWatchlistMapper;
    @Mock
    private StockWatchlistItemMapper stockWatchlistItemMapper;

    private StockDashboardQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StockDashboardQueryServiceImpl(
                stockBaseMapper,
                stockSignalDailyMapper,
                stockDailyQuoteMapper,
                stockActualResultMapper,
                stockWatchlistMapper,
                stockWatchlistItemMapper
        );
    }

    @Test
    void normalizesDashboardQueryDefaultsAndBounds() {
        StockConsoleVo.SignalDashboardQuery defaults = new StockConsoleVo.SignalDashboardQuery(
                null, " ", null, null, null, null, null, null, 0, 0, null, "sideways"
        );
        StockConsoleVo.SignalDashboardQuery bounded = new StockConsoleVo.SignalDashboardQuery(
                DATE, "港股", "growth", null, null, null, null, null, 2, 500, "price", "ASC"
        );

        assertThat(defaults.market()).isEqualTo("A股");
        assertThat(defaults.poolCode()).isEqualTo("all");
        assertThat(defaults.pageNum()).isEqualTo(1);
        assertThat(defaults.pageSize()).isEqualTo(20);
        assertThat(defaults.sortField()).isEqualTo("confidence");
        assertThat(defaults.sortOrder()).isEqualTo("desc");
        assertThat(bounded.pageSize()).isEqualTo(100);
        assertThat(bounded.sortOrder()).isEqualTo("asc");
    }

    @Test
    void appliesPoolMarketDateSignalIndustryConfidenceAndCodeSearch() {
        StockBase pingAn = stock("000001.SZ", "平安银行", "A股", "银行");
        StockBase otherBank = stock("600000.SH", "浦发银行", "A股", "银行");
        StockBase tech = stock("000002.SZ", "科技股份", "A股", "科技");
        StockBase hk = stock("00700.HK", "腾讯控股", "港股", "科技");
        when(stockBaseMapper.selectList(any())).thenReturn(List.of(pingAn, otherBank, tech));
        when(stockWatchlistMapper.selectOne(any())).thenReturn(StockWatchlist.builder().id(7L).poolCode("focus").market("A股").build());
        when(stockWatchlistItemMapper.selectList(any())).thenReturn(List.of(
                StockWatchlistItem.builder().watchlistId(7L).symbol("000001.SZ").build(),
                StockWatchlistItem.builder().watchlistId(7L).symbol("000002.SZ").build()
        ));
        when(stockSignalDailyMapper.selectList(any())).thenReturn(List.of(
                signal("000001.SZ", DATE, "bullish", "0.80", 2, LocalDateTime.of(2026, 7, 10, 15, 1)),
                signal("000001.SZ", DATE.minusDays(1), "bullish", "0.80", 2, null),
                signal("000002.SZ", DATE, "bearish", "0.85", 1, null),
                signal("600000.SH", DATE, "bullish", "0.90", 1, null),
                signal("00700.HK", DATE, "bullish", "0.90", 1, null),
                signal("000001.SZ", DATE, "bullish", "0.60", 1, null)
        ));
        when(stockDailyQuoteMapper.selectList(any())).thenReturn(List.of());
        when(stockActualResultMapper.selectList(any())).thenReturn(List.of());

        StockConsoleVo.SignalDashboardOverview result = service.dashboard(new StockConsoleVo.SignalDashboardQuery(
                DATE, "A股", "focus", "sz000001", "bullish", "银行",
                new BigDecimal("0.70"), new BigDecimal("0.85"), 1, 20, "confidence", "desc"
        ));

        assertThat(result.signals()).extracting(StockConsoleVo.SignalRow::symbol).containsExactly("000001.SZ");
        assertThat(result.tradeDate()).isEqualTo(DATE);
        assertThat(result.availableIndustries()).containsExactly("科技", "银行");
        assertThat(result.riskDisclaimer()).isEqualTo(StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
        assertThat(result.dataUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 10, 15, 1));
        verify(stockBaseMapper).selectList(any());
        verify(stockWatchlistMapper).selectOne(any());
        verify(stockWatchlistItemMapper).selectList(any());
        verify(stockSignalDailyMapper).selectList(any());
        verify(stockDailyQuoteMapper).selectList(any());
        verify(stockActualResultMapper).selectList(any());
    }

    @Test
    void searchesByPartialNameAndResolvesLatestSignalDate() {
        when(stockSignalDailyMapper.selectOne(any())).thenReturn(signal("000001.SZ", DATE, "bullish", "0.80", 1, null));
        when(stockBaseMapper.selectList(any())).thenReturn(List.of(
                stock("000001.SZ", "平安银行", "A股", "银行"),
                stock("600000.SH", "浦发银行", "A股", "银行")
        ));
        when(stockSignalDailyMapper.selectList(any())).thenReturn(List.of(
                signal("000001.SZ", DATE, "bullish", "0.80", 1, null),
                signal("600000.SH", DATE, "watch", "0.70", 1, null)
        ));
        when(stockDailyQuoteMapper.selectList(any())).thenReturn(List.of());
        when(stockActualResultMapper.selectList(any())).thenReturn(List.of());

        StockConsoleVo.SignalDashboardOverview result = service.dashboard(query(null, "平安", 1, 20, "symbol", "asc"));

        assertThat(result.tradeDate()).isEqualTo(DATE);
        assertThat(result.signals()).extracting(StockConsoleVo.SignalRow::name).containsExactly("平安银行");
        verify(stockSignalDailyMapper).selectOne(any());
    }

    @Test
    void paginatesAfterSortingAndComputesMetricsFromAllFilteredSignals() {
        List<StockBase> stocks = List.of(
                stock("000001.SZ", "甲", "A股", "银行"),
                stock("000002.SZ", "乙", "A股", "科技"),
                stock("000003.SZ", "丙", "A股", "消费"),
                stock("000004.SZ", "丁", "A股", "医药")
        );
        when(stockBaseMapper.selectList(any())).thenReturn(stocks);
        when(stockSignalDailyMapper.selectList(any())).thenReturn(List.of(
                signal("000004.SZ", DATE, "high_risk", "0.40", 1, LocalDateTime.of(2026, 7, 10, 15, 0)),
                signal("000002.SZ", DATE, "bearish", "0.90", 3, LocalDateTime.of(2026, 7, 10, 15, 2)),
                signal("000001.SZ", DATE, "bullish", "0.90", 2, LocalDateTime.of(2026, 7, 10, 15, 1)),
                signal("000003.SZ", DATE, "watch", "0.70", 1, null)
        ));
        when(stockDailyQuoteMapper.selectList(any())).thenReturn(List.of(
                quote("000001.SZ", "10.25", "1.50", LocalDateTime.of(2026, 7, 10, 15, 10)),
                quote("000002.SZ", "20.50", "-2.00", LocalDateTime.of(2026, 7, 10, 15, 8))
        ));
        when(stockActualResultMapper.selectList(any())).thenReturn(List.of(
                actual("000001.SZ", true),
                actual("000002.SZ", false),
                StockActualResult.builder().symbol("000003.SZ").signalDate(DATE).hit5d(null).build(),
                actual("999999.SZ", true)
        ));

        StockConsoleVo.SignalDashboardOverview result = service.dashboard(query(DATE, null, 2, 2, "notAllowed", "desc"));
        Map<String, StockConsoleVo.MetricCard> metrics = result.metrics().stream()
                .collect(Collectors.toMap(StockConsoleVo.MetricCard::label, Function.identity()));

        assertThat(result.total()).isEqualTo(4);
        assertThat(result.pageNum()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(2);
        assertThat(result.signals()).extracting(StockConsoleVo.SignalRow::symbol)
                .containsExactly("000003.SZ", "000004.SZ");
        assertThat(metrics.get("关注股票").value()).isEqualByComparingTo("4");
        assertThat(metrics.get("产生信号").value()).isEqualByComparingTo("4");
        assertThat(metrics.get("看涨").value()).isEqualByComparingTo("1");
        assertThat(metrics.get("看跌").value()).isEqualByComparingTo("1");
        assertThat(metrics.get("观望").value()).isEqualByComparingTo("1");
        assertThat(metrics.get("高风险").value()).isEqualByComparingTo("1");
        assertThat(metrics.get("命中率（5日）").value()).isEqualByComparingTo("50.00");
        assertThat(result.dataUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 10, 15, 10));
        assertThat(result.marketContext().available()).isFalse();
        assertThat(result.marketContext().indexValue()).isNull();
    }

    @Test
    void joinsDailyQuoteAndKeepsMissingQuoteValuesNull() {
        when(stockBaseMapper.selectList(any())).thenReturn(List.of(
                stock("000001.SZ", "甲", "A股", "银行"),
                stock("000002.SZ", "乙", "A股", "科技")
        ));
        when(stockSignalDailyMapper.selectList(any())).thenReturn(List.of(
                signal("000001.SZ", DATE, "bullish", "0.80", 1, null),
                signal("000002.SZ", DATE, "bearish", "0.70", 1, null)
        ));
        when(stockDailyQuoteMapper.selectList(any())).thenReturn(List.of(
                quote("000001.SZ", "10.25", "1.50", LocalDateTime.of(2026, 7, 10, 15, 10)),
                StockDailyQuote.builder().symbol("000002.SZ").tradeDate(DATE.minusDays(1)).closePrice(new BigDecimal("99")).build()
        ));
        when(stockActualResultMapper.selectList(any())).thenReturn(List.of());

        StockConsoleVo.SignalDashboardOverview result = service.dashboard(query(DATE, null, 1, 20, "price", "asc"));

        StockConsoleVo.SignalRow priced = result.signals().get(0);
        StockConsoleVo.SignalRow missing = result.signals().get(1);
        assertThat(priced.symbol()).isEqualTo("000001.SZ");
        assertThat(priced.price()).isEqualByComparingTo("10.25");
        assertThat(priced.changePct()).isEqualByComparingTo("1.50");
        assertThat(missing.symbol()).isEqualTo("000002.SZ");
        assertThat(missing.price()).isNull();
        assertThat(missing.changePct()).isNull();
    }

    @Test
    void returnsEmptyDashboardWithoutQueryingDependentTablesWhenCandidatesAreEmpty() {
        when(stockBaseMapper.selectList(any())).thenReturn(List.of());

        StockConsoleVo.SignalDashboardOverview result = service.dashboard(query(DATE, null, 1, 20, "confidence", "desc"));

        assertThat(result.total()).isZero();
        assertThat(result.signals()).isEmpty();
        assertThat(result.availableIndustries()).isEmpty();
        assertThat(result.dataUpdatedAt()).isNull();
        assertThat(result.marketContext().available()).isFalse();
        assertThat(result.marketContext().indexValue()).isNull();
        assertThat(result.metrics()).extracting(StockConsoleVo.MetricCard::label)
                .contains("关注股票", "产生信号", "看涨", "看跌", "观望", "高风险");
        verify(stockSignalDailyMapper, never()).selectList(any());
        verify(stockDailyQuoteMapper, never()).selectList(any());
        verify(stockActualResultMapper, never()).selectList(any());
    }

    @Test
    void rejectsUnknownPersistentPool() {
        when(stockBaseMapper.selectList(any())).thenReturn(List.of(stock("000001.SZ", "甲", "A股", "银行")));
        when(stockWatchlistMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.dashboard(new StockConsoleVo.SignalDashboardQuery(
                DATE, "A股", "missing", null, null, null, null, null, 1, 20, null, null
        ))).isInstanceOf(ServiceException.class).hasMessageContaining("股票池");

        verify(stockWatchlistItemMapper, never()).selectList(any());
        verify(stockSignalDailyMapper, never()).selectList(any());
    }

    @Test
    void excludesMissingConfidenceWhenConfidenceRangeIsPresent() {
        when(stockBaseMapper.selectList(any())).thenReturn(List.of(stock("000001.SZ", "甲", "A股", "银行")));
        when(stockSignalDailyMapper.selectList(any())).thenReturn(List.of(StockSignalDaily.builder()
                .symbol("000001.SZ")
                .signalDate(DATE)
                .signal("watch")
                .confidence(null)
                .build()));

        StockConsoleVo.SignalDashboardOverview result = service.dashboard(new StockConsoleVo.SignalDashboardQuery(
                DATE, "A股", "all", null, null, null, null, new BigDecimal("0.80"),
                1, 20, "confidence", "desc"
        ));

        assertThat(result.total()).isZero();
        assertThat(result.signals()).isEmpty();
        verify(stockDailyQuoteMapper, never()).selectList(any());
        verify(stockActualResultMapper, never()).selectList(any());
    }

    @Test
    void returnsEmptyPageForPageNumberBeyondIntegerOffsetRange() {
        when(stockBaseMapper.selectList(any())).thenReturn(List.of(stock("000001.SZ", "甲", "A股", "银行")));
        when(stockSignalDailyMapper.selectList(any())).thenReturn(List.of(
                signal("000001.SZ", DATE, "bullish", "0.80", 1, null)
        ));
        when(stockDailyQuoteMapper.selectList(any())).thenReturn(List.of());
        when(stockActualResultMapper.selectList(any())).thenReturn(List.of());

        StockConsoleVo.SignalDashboardOverview result = service.dashboard(query(
                DATE, null, Integer.MAX_VALUE, 100, "confidence", "desc"
        ));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.signals()).isEmpty();
    }

    @Test
    void reportsMissingHitRateWhenNoHit5dSampleIsCompleted() {
        when(stockBaseMapper.selectList(any())).thenReturn(List.of(stock("000001.SZ", "甲", "A股", "银行")));
        when(stockSignalDailyMapper.selectList(any())).thenReturn(List.of(
                signal("000001.SZ", DATE, "bullish", "0.80", 1, null)
        ));
        when(stockDailyQuoteMapper.selectList(any())).thenReturn(List.of());
        when(stockActualResultMapper.selectList(any())).thenReturn(List.of(
                StockActualResult.builder().symbol("000001.SZ").signalDate(DATE).hit5d(null).build()
        ));

        StockConsoleVo.SignalDashboardOverview result = service.dashboard(query(
                DATE, null, 1, 20, "confidence", "desc"
        ));

        StockConsoleVo.MetricCard hitRate = result.metrics().stream()
                .filter(metric -> "命中率（5日）".equals(metric.label()))
                .findFirst()
                .orElseThrow();
        assertThat(hitRate.value()).isNull();
    }

    @Test
    void marketContextContractCarriesStructuredSentimentAndRiskOverview() {
        StockConsoleVo.Sentiment sentiment = new StockConsoleVo.Sentiment(
                "中性", new BigDecimal("50.00"), "available"
        );
        StockConsoleVo.RiskOverview riskOverview = new StockConsoleVo.RiskOverview(
                3, new BigDecimal("12.50"), "synced", "medium", "风险分布正常"
        );
        StockConsoleVo.MarketContext compatible = new StockConsoleVo.MarketContext(
                "测试指数", BigDecimal.ONE, BigDecimal.ZERO, "平稳", List.of()
        );

        assertThat(sentiment.label()).isEqualTo("中性");
        assertThat(riskOverview.highRiskCount()).isEqualTo(3);
        assertThat(riskOverview.highRiskRatio()).isEqualByComparingTo("12.50");
        assertThat(riskOverview.syncStatus()).isEqualTo("synced");
        assertThat(compatible.available()).isTrue();

        when(stockBaseMapper.selectList(any())).thenReturn(List.of());
        StockConsoleVo.MarketContext emptyContext = service.dashboard(query(
                DATE, null, 1, 20, "confidence", "desc"
        )).marketContext();

        assertThat(emptyContext.available()).isFalse();
        assertThat(emptyContext.sentiment()).isEqualTo(new StockConsoleVo.Sentiment("暂无数据", null, "unavailable"));
        assertThat(emptyContext.riskOverview()).isEqualTo(new StockConsoleVo.RiskOverview(
                0, null, "unavailable", null, "暂无市场风险环境数据"
        ));
    }

    private StockConsoleVo.SignalDashboardQuery query(
            LocalDate date,
            String symbol,
            int pageNum,
            int pageSize,
            String sortField,
            String sortOrder
    ) {
        return new StockConsoleVo.SignalDashboardQuery(
                date, "A股", "all", symbol, null, null, null, null, pageNum, pageSize, sortField, sortOrder
        );
    }

    private StockBase stock(String symbol, String name, String market, String industry) {
        return StockBase.builder().symbol(symbol).name(name).market(market).industry(industry).build();
    }

    private StockSignalDaily signal(
            String symbol,
            LocalDate date,
            String signal,
            String confidence,
            int ruleCount,
            LocalDateTime createdTime
    ) {
        return StockSignalDaily.builder()
                .symbol(symbol)
                .signalDate(date)
                .signal(signal)
                .bullishScore(new BigDecimal("0.60"))
                .bearishScore(new BigDecimal("0.20"))
                .riskScore(new BigDecimal("0.10"))
                .confidence(new BigDecimal(confidence))
                .triggeredRules(java.util.stream.IntStream.range(0, ruleCount)
                        .mapToObj(index -> "R" + index)
                        .collect(Collectors.joining(",")))
                .createdTime(createdTime)
                .build();
    }

    private StockDailyQuote quote(String symbol, String price, String changePct, LocalDateTime syncTime) {
        return StockDailyQuote.builder()
                .symbol(symbol)
                .tradeDate(DATE)
                .closePrice(new BigDecimal(price))
                .changePct(new BigDecimal(changePct))
                .syncTime(syncTime)
                .build();
    }

    private StockActualResult actual(String symbol, boolean hit5d) {
        return StockActualResult.builder().symbol(symbol).signalDate(DATE).hit5d(hit5d).build();
    }
}
