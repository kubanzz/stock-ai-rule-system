package com.jx.tracker.market.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.entity.MarketDataSyncRun;
import com.jx.tracker.domain.enums.MarketDataSyncStatus;
import com.jx.tracker.domain.enums.MarketDataSyncType;
import com.jx.tracker.mapper.MarketDataSyncRunMapper;
import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataImportResultDto;
import com.jx.tracker.market.data.dto.MarketDataSyncRequestDto;
import com.jx.tracker.market.data.dto.StockBaseUpsertDto;
import com.jx.tracker.market.data.dto.StockDailyQuoteUpsertDto;
import com.jx.tracker.market.data.dto.TradeCalendarDto;
import com.jx.tracker.market.data.provider.CsvMarketDataProvider;
import com.jx.tracker.market.data.provider.MarketDataProvider;
import com.jx.tracker.market.data.provider.MarketDataProviderResolver;
import com.jx.tracker.market.data.provider.MarketDataProviderSelection;
import com.jx.tracker.market.data.service.StockBaseService;
import com.jx.tracker.market.data.service.StockDailyQuoteService;
import com.jx.tracker.market.data.service.TradeCalendarService;
import com.jx.tracker.market.data.service.impl.MarketDataSyncServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataSyncServiceTest {

    @Test
    void syncStockListFetchesProviderDataAndRecordsSuccessfulRun() {
        MarketDataProvider provider = mock(MarketDataProvider.class);
        when(provider.fetchStockList()).thenReturn(List.of(stock("000001.SZ")));
        MarketDataImportResultDto<StockBaseUpsertDto> importResult = new MarketDataImportResultDto<>();
        importResult.accept(stock("000001.SZ"));
        importResult.markInserted();

        StockBaseService stockBaseService = mock(StockBaseService.class);
        when(stockBaseService.upsertStockBases(any())).thenReturn(importResult);

        MarketDataSyncServiceImpl service = service(provider, stockBaseService, null, null, syncRunMapper());

        MarketDataSyncRequestDto request = new MarketDataSyncRequestDto();
        request.setTriggerType("manual");
        request.setTriggerBy("tester");

        var result = service.syncStockList(request);

        assertThat(result.getRunId()).isEqualTo(100L);
        assertThat(result.getSyncType()).isEqualTo(MarketDataSyncType.STOCK_LIST.getCode());
        assertThat(result.getStatus()).isEqualTo(MarketDataSyncStatus.SUCCESS.getCode());
        assertThat(result.getScanned()).isEqualTo(1);
        assertThat(result.getInserted()).isEqualTo(1);
        assertThat(result.getDataSource()).isEqualTo("mock");
    }

    @Test
    void syncDailyQuotesRecordsTargetSymbolAndDateRange() {
        MarketDataProvider provider = mock(MarketDataProvider.class);
        when(provider.fetchDailyQuotes("000001.SZ", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2)))
                .thenReturn(List.of(quote("000001.SZ", LocalDate.of(2026, 7, 1))));
        MarketDataImportResultDto<StockDailyQuoteUpsertDto> importResult = new MarketDataImportResultDto<>();
        importResult.accept(quote("000001.SZ", LocalDate.of(2026, 7, 1)));
        importResult.markUpdated();

        StockDailyQuoteService quoteService = mock(StockDailyQuoteService.class);
        when(quoteService.upsertDailyQuotes(any())).thenReturn(importResult);
        MarketDataSyncRunMapper syncRunMapper = syncRunMapper();
        MarketDataSyncServiceImpl service = service(provider, null, quoteService, null, syncRunMapper);

        DailyQuoteSyncRequestDto request = new DailyQuoteSyncRequestDto();
        request.setTargetSymbol("000001.SZ");
        request.setStartDate(LocalDate.of(2026, 7, 1));
        request.setEndDate(LocalDate.of(2026, 7, 2));

        var result = service.syncDailyQuotes(request);

        assertThat(result.getSyncType()).isEqualTo(MarketDataSyncType.DAILY_QUOTE.getCode());
        assertThat(result.getTargetSymbol()).isEqualTo("000001.SZ");
        assertThat(result.getStartDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(result.getEndDate()).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(result.getUpdated()).isEqualTo(1);

        ArgumentCaptor<MarketDataSyncRun> captor = ArgumentCaptor.forClass(MarketDataSyncRun.class);
        verify(syncRunMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(MarketDataSyncStatus.SUCCESS.getCode());
        assertThat(captor.getValue().getTargetSymbol()).isEqualTo("000001.SZ");
    }

    @Test
    void syncDailyQuotesIncludesCsvRejectedRowsInRunCounts() {
        String csv = """
                symbol,trade_date,open_price,high_price,low_price,close_price,volume,amount,change_pct
                sz000001,2026-07-01,10.00,10.80,9.90,10.50,100000,1050000,1.20
                sz000001,2026-07-02,10.00,9.80,10.20,10.10,100000,1010000,0.30
                """;
        CsvMarketDataProvider provider = CsvMarketDataProvider.fromDailyQuoteCsv(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        MarketDataImportResultDto<StockDailyQuoteUpsertDto> saved = new MarketDataImportResultDto<>();
        saved.accept(quote("000001.SZ", LocalDate.of(2026, 7, 1)));
        saved.markInserted();
        StockDailyQuoteService quoteService = mock(StockDailyQuoteService.class);
        when(quoteService.upsertDailyQuotes(any())).thenReturn(saved);
        MarketDataSyncRunMapper syncRunMapper = syncRunMapper();
        MarketDataSyncServiceImpl service = service(provider, null, quoteService, null, syncRunMapper);

        DailyQuoteSyncRequestDto request = new DailyQuoteSyncRequestDto();
        request.setTargetSymbol("000001.SZ");
        request.setStartDate(LocalDate.of(2026, 7, 1));
        request.setEndDate(LocalDate.of(2026, 7, 2));

        var result = service.syncDailyQuotes(request);

        assertThat(result.getStatus()).isEqualTo(MarketDataSyncStatus.SUCCESS.getCode());
        assertThat(result.getScanned()).isEqualTo(2);
        assertThat(result.getInserted()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getErrors()).anySatisfy(error -> assertThat(error).contains("high_price"));

        ArgumentCaptor<MarketDataSyncRun> captor = ArgumentCaptor.forClass(MarketDataSyncRun.class);
        verify(syncRunMapper).updateById(captor.capture());
        assertThat(captor.getValue().getErrorMessage()).contains("high_price");
    }

    @Test
    void syncStockListRollsBackBusinessTransactionWhenPersistenceFailsAndRecordsFailure() {
        MarketDataProvider provider = mock(MarketDataProvider.class);
        when(provider.fetchStockList()).thenReturn(List.of(stock("000001.SZ")));
        StockBaseService stockBaseService = mock(StockBaseService.class);
        when(stockBaseService.upsertStockBases(any())).thenThrow(new IllegalStateException("db write failed"));
        CountingTransactionManager transactionManager = new CountingTransactionManager();
        MarketDataSyncRunMapper syncRunMapper = syncRunMapper();
        MarketDataSyncServiceImpl service = service(
                provider,
                stockBaseService,
                null,
                null,
                syncRunMapper,
                new TransactionTemplate(transactionManager)
        );

        var result = service.syncStockList(new MarketDataSyncRequestDto());

        assertThat(result.getStatus()).isEqualTo(MarketDataSyncStatus.FAILED.getCode());
        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getErrors()).contains("db write failed");
        assertThat(transactionManager.commits).isZero();
        assertThat(transactionManager.rollbacks).isEqualTo(1);

        ArgumentCaptor<MarketDataSyncRun> captor = ArgumentCaptor.forClass(MarketDataSyncRun.class);
        verify(syncRunMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(MarketDataSyncStatus.FAILED.getCode());
    }

    @Test
    void syncTradeCalendarUsesCalendarServiceAndRecordsCounts() {
        MarketDataProvider provider = mock(MarketDataProvider.class);
        when(provider.fetchTradeCalendar(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2)))
                .thenReturn(List.of(calendar(LocalDate.of(2026, 7, 1)), calendar(LocalDate.of(2026, 7, 2))));
        MarketDataImportResultDto<TradeCalendarDto> importResult = new MarketDataImportResultDto<>();
        importResult.accept(calendar(LocalDate.of(2026, 7, 1)));
        importResult.accept(calendar(LocalDate.of(2026, 7, 2)));
        importResult.markInserted();
        importResult.markInserted();

        TradeCalendarService tradeCalendarService = mock(TradeCalendarService.class);
        when(tradeCalendarService.upsertTradeCalendars(any())).thenReturn(importResult);
        MarketDataSyncServiceImpl service = service(provider, null, null, tradeCalendarService, syncRunMapper());

        MarketDataSyncRequestDto request = new MarketDataSyncRequestDto();
        request.setStartDate(LocalDate.of(2026, 7, 1));
        request.setEndDate(LocalDate.of(2026, 7, 2));

        var result = service.syncTradeCalendar(request);

        assertThat(result.getSyncType()).isEqualTo(MarketDataSyncType.TRADE_CALENDAR.getCode());
        assertThat(result.getScanned()).isEqualTo(2);
        assertThat(result.getInserted()).isEqualTo(2);
        assertThat(result.getFailed()).isZero();
    }

    private MarketDataSyncServiceImpl service(
            MarketDataProvider provider,
            StockBaseService stockBaseService,
            StockDailyQuoteService quoteService,
            TradeCalendarService tradeCalendarService,
            MarketDataSyncRunMapper syncRunMapper) {
        return service(provider, stockBaseService, quoteService, tradeCalendarService, syncRunMapper, transactionTemplate());
    }

    private MarketDataSyncServiceImpl service(
            MarketDataProvider provider,
            StockBaseService stockBaseService,
            StockDailyQuoteService quoteService,
            TradeCalendarService tradeCalendarService,
            MarketDataSyncRunMapper syncRunMapper,
            TransactionTemplate transactionTemplate) {
        MarketDataProviderResolver resolver = mock(MarketDataProviderResolver.class);
        when(resolver.resolve()).thenReturn(new MarketDataProviderSelection(provider, "mock", false, null));
        return new MarketDataSyncServiceImpl(
                resolver,
                stockBaseService == null ? mock(StockBaseService.class) : stockBaseService,
                quoteService == null ? mock(StockDailyQuoteService.class) : quoteService,
                tradeCalendarService == null ? mock(TradeCalendarService.class) : tradeCalendarService,
                syncRunMapper,
                new ObjectMapper(),
                transactionTemplate
        );
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        });
    }

    private static class CountingTransactionManager extends AbstractPlatformTransactionManager {

        private int commits;

        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }

    private MarketDataSyncRunMapper syncRunMapper() {
        MarketDataSyncRunMapper mapper = mock(MarketDataSyncRunMapper.class);
        when(mapper.insert(any(MarketDataSyncRun.class))).thenAnswer(invocation -> {
            MarketDataSyncRun run = invocation.getArgument(0);
            run.setId(100L);
            return 1;
        });
        return mapper;
    }

    private StockBaseUpsertDto stock(String symbol) {
        StockBaseUpsertDto dto = new StockBaseUpsertDto();
        dto.setSymbol(symbol);
        dto.setName("平安银行");
        dto.setMarket("CN");
        dto.setDataSource("mock");
        return dto;
    }

    private StockDailyQuoteUpsertDto quote(String symbol, LocalDate tradeDate) {
        StockDailyQuoteUpsertDto dto = new StockDailyQuoteUpsertDto();
        dto.setSymbol(symbol);
        dto.setTradeDate(tradeDate);
        dto.setOpenPrice(new BigDecimal("10.00"));
        dto.setHighPrice(new BigDecimal("10.80"));
        dto.setLowPrice(new BigDecimal("9.90"));
        dto.setClosePrice(new BigDecimal("10.50"));
        return dto;
    }

    private TradeCalendarDto calendar(LocalDate date) {
        TradeCalendarDto dto = new TradeCalendarDto();
        dto.setMarket("CN");
        dto.setTradeDate(date);
        dto.setOpen(true);
        dto.setDataSource("mock");
        return dto;
    }
}
