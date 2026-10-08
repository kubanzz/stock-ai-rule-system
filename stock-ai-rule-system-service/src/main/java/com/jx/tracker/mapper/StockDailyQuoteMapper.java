package com.jx.tracker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jx.tracker.domain.entity.StockDailyQuote;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface StockDailyQuoteMapper extends BaseMapper<StockDailyQuote> {

    /** 每只股票取截至看板日期的最新可用收盘行情，兼容停牌股票的较早交易日。 */
    @Select("""
            <script>
            SELECT quote.*, quote.created_at AS createdTime FROM stock_daily_quote quote
            JOIN (
                SELECT symbol, MAX(trade_date) AS trade_date
                FROM stock_daily_quote
                WHERE trade_date &lt;= #{tradeDate} AND close_price IS NOT NULL
                  AND symbol IN
                  <foreach collection="symbols" item="symbol" open="(" separator="," close=")">
                    #{symbol}
                  </foreach>
                GROUP BY symbol
            ) latest ON latest.symbol = quote.symbol AND latest.trade_date = quote.trade_date
            </script>
            """)
    List<StockDailyQuote> selectLatestForDashboard(@Param("symbols") List<String> symbols,
                                                 @Param("tradeDate") LocalDate tradeDate);
}
