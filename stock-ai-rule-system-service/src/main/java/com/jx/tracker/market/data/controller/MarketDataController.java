package com.jx.tracker.market.data.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.market.data.dto.MarketDataImportResultDto;
import com.jx.tracker.market.data.dto.StockBaseQueryDto;
import com.jx.tracker.market.data.dto.StockBaseUpsertDto;
import com.jx.tracker.market.data.dto.StockDailyQuoteQueryDto;
import com.jx.tracker.market.data.dto.StockDailyQuoteUpsertDto;
import com.jx.tracker.market.data.provider.CsvMarketDataProvider;
import com.jx.tracker.market.data.provider.MarketDataProvider;
import com.jx.tracker.market.data.service.StockBaseService;
import com.jx.tracker.market.data.service.StockDailyQuoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/market-data")
@Tag(name = "M1 行情与基础数据采集")
public class MarketDataController {

    private final StockBaseService stockBaseService;

    private final StockDailyQuoteService stockDailyQuoteService;

    private final MarketDataProvider mockMarketDataProvider;

    public MarketDataController(
            StockBaseService stockBaseService,
            StockDailyQuoteService stockDailyQuoteService,
            MarketDataProvider mockMarketDataProvider) {
        this.stockBaseService = stockBaseService;
        this.stockDailyQuoteService = stockDailyQuoteService;
        this.mockMarketDataProvider = mockMarketDataProvider;
    }

    @GetMapping("/stocks")
    @Operation(summary = "分页查询股票基础信息")
    public PageResult<StockBase> pageStocks(@ModelAttribute StockBaseQueryDto query) {
        return stockBaseService.pageStocks(query);
    }

    @GetMapping("/stocks/{symbol}")
    @Operation(summary = "按股票代码和市场查询股票基础信息")
    public AjaxResult getStock(@PathVariable("symbol") String symbol, @RequestParam("market") String market) {
        return AjaxResult.success(stockBaseService.getBySymbolAndMarket(symbol, market));
    }

    @PostMapping("/stocks")
    @Operation(summary = "按股票代码和市场幂等写入股票基础信息")
    public AjaxResult upsertStock(@RequestBody StockBaseUpsertDto request) {
        return AjaxResult.success(stockBaseService.upsertStockBases(Collections.singletonList(request)));
    }

    @PostMapping("/stocks/batch")
    @Operation(summary = "批量幂等写入股票基础信息")
    public AjaxResult upsertStocks(@RequestBody List<StockBaseUpsertDto> request) {
        return AjaxResult.success(stockBaseService.upsertStockBases(request));
    }

    @PostMapping("/stocks/import/csv")
    @Operation(summary = "CSV 导入股票基础信息")
    public AjaxResult importStockCsv(@RequestParam("file") MultipartFile file) {
        CsvMarketDataProvider provider = stockCsvProvider(file);
        MarketDataImportResultDto<StockBaseUpsertDto> parsed = provider.importStockBases();
        MarketDataImportResultDto<StockBaseUpsertDto> saved = stockBaseService.upsertStockBases(parsed.getAcceptedRows());
        saved.getRejectedRows().addAll(parsed.getRejectedRows());
        saved.setTotalRows(parsed.getTotalRows());
        return AjaxResult.success(saved);
    }

    @GetMapping("/daily-quotes")
    @Operation(summary = "分页查询日 K 行情")
    public PageResult<StockDailyQuote> pageDailyQuotes(@ModelAttribute StockDailyQuoteQueryDto query) {
        return stockDailyQuoteService.pageDailyQuotes(query);
    }

    @GetMapping("/daily-quotes/{symbol}/{tradeDate}")
    @Operation(summary = "按股票代码和交易日查询日 K 行情")
    public AjaxResult getDailyQuote(@PathVariable("symbol") String symbol, @PathVariable("tradeDate") LocalDate tradeDate) {
        return AjaxResult.success(stockDailyQuoteService.getBySymbolAndTradeDate(symbol, tradeDate));
    }

    @PostMapping("/daily-quotes")
    @Operation(summary = "按股票代码和交易日幂等写入日 K 行情")
    public AjaxResult upsertDailyQuote(@RequestBody StockDailyQuoteUpsertDto request) {
        return AjaxResult.success(stockDailyQuoteService.upsertDailyQuotes(Collections.singletonList(request)));
    }

    @PostMapping("/daily-quotes/batch")
    @Operation(summary = "批量幂等写入日 K 行情")
    public AjaxResult upsertDailyQuotes(@RequestBody List<StockDailyQuoteUpsertDto> request) {
        return AjaxResult.success(stockDailyQuoteService.upsertDailyQuotes(request));
    }

    @PostMapping("/daily-quotes/import/csv")
    @Operation(summary = "CSV 导入日 K 行情")
    public AjaxResult importDailyQuoteCsv(@RequestParam("file") MultipartFile file) {
        CsvMarketDataProvider provider = dailyQuoteCsvProvider(file);
        MarketDataImportResultDto<StockDailyQuoteUpsertDto> parsed = provider.importDailyQuotes();
        MarketDataImportResultDto<StockDailyQuoteUpsertDto> saved = stockDailyQuoteService.upsertDailyQuotes(parsed.getAcceptedRows());
        saved.getRejectedRows().addAll(parsed.getRejectedRows());
        saved.setTotalRows(parsed.getTotalRows());
        return AjaxResult.success(saved);
    }

    @PostMapping("/import/mock")
    @Operation(summary = "导入 mock 股票基础信息与日 K 行情")
    public AjaxResult importMockData(
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "startDate", required = false) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) LocalDate endDate) {
        MarketDataImportResultDto<StockBaseUpsertDto> stocks = stockBaseService.upsertStockBases(mockMarketDataProvider.fetchStockBases());
        MarketDataImportResultDto<StockDailyQuoteUpsertDto> quotes =
                stockDailyQuoteService.upsertDailyQuotes(mockMarketDataProvider.fetchDailyQuotes(symbol, startDate, endDate));
        return AjaxResult.success("导入完成", new MockImportResult(stocks, quotes));
    }

    private CsvMarketDataProvider stockCsvProvider(MultipartFile file) {
        try {
            return CsvMarketDataProvider.fromStockBaseCsv(file.getInputStream());
        } catch (IOException e) {
            throw new ServiceException("读取股票基础信息 CSV 失败", e);
        }
    }

    private CsvMarketDataProvider dailyQuoteCsvProvider(MultipartFile file) {
        try {
            return CsvMarketDataProvider.fromDailyQuoteCsv(file.getInputStream());
        } catch (IOException e) {
            throw new ServiceException("读取日 K 行情 CSV 失败", e);
        }
    }

    private record MockImportResult(
            MarketDataImportResultDto<StockBaseUpsertDto> stocks,
            MarketDataImportResultDto<StockDailyQuoteUpsertDto> dailyQuotes) {
    }
}
