package com.jx.tracker.market.data;

import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.mapper.StockBaseMapper;
import com.jx.tracker.market.data.dto.StockBaseUpsertDto;
import com.jx.tracker.market.data.service.impl.StockBaseServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockBaseServiceTest {

    @Test
    void upsertStockBaseRetriesAsUpdateWhenConcurrentInsertWins() {
        StockBaseMapper mapper = mock(StockBaseMapper.class);
        StockBase existing = StockBase.builder()
                .id(100L)
                .symbol("000001.SZ")
                .market("CN")
                .name("平安银行")
                .build();
        when(mapper.selectOne(any())).thenReturn(null, existing);
        when(mapper.insert(any(StockBase.class))).thenThrow(new DuplicateKeyException("duplicate symbol"));
        StockBaseServiceImpl service = new StockBaseServiceImpl(mapper);

        var result = service.upsertStockBases(List.of(stock("sz000001")));

        assertThat(result.getInsertedRows()).isZero();
        assertThat(result.getUpdatedRows()).isEqualTo(1);
        verify(mapper).updateById(any(StockBase.class));
    }

    private StockBaseUpsertDto stock(String symbol) {
        StockBaseUpsertDto dto = new StockBaseUpsertDto();
        dto.setSymbol(symbol);
        dto.setName("平安银行");
        dto.setMarket("CN");
        dto.setExchange("SZSE");
        dto.setStatus("active");
        dto.setDataSource("mock");
        return dto;
    }
}
