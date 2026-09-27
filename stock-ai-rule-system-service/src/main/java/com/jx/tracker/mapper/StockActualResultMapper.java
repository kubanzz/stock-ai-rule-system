package com.jx.tracker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jx.tracker.domain.entity.StockActualResult;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockActualResultMapper extends BaseMapper<StockActualResult> {

    @Insert("""
            INSERT INTO stock_actual_result (
                symbol, signal_date, return_1d, return_3d, return_5d, return_10d, hit_1d, hit_5d
            ) VALUES (
                #{actualResult.symbol}, #{actualResult.signalDate}, #{actualResult.return1d},
                #{actualResult.return3d}, #{actualResult.return5d}, #{actualResult.return10d},
                #{actualResult.hit1d}, #{actualResult.hit5d}
            ) ON DUPLICATE KEY UPDATE
                return_1d = VALUES(return_1d),
                return_3d = VALUES(return_3d),
                return_5d = VALUES(return_5d),
                return_10d = VALUES(return_10d),
                hit_1d = VALUES(hit_1d),
                hit_5d = VALUES(hit_5d)
            """)
    int upsertActualResult(@Param("actualResult") StockActualResult actualResult);
}
