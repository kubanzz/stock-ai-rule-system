package com.jx.tracker.service.impl;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.entity.StockWatchlist;
import com.jx.tracker.domain.entity.StockWatchlistItem;
import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.StockBaseMapper;
import com.jx.tracker.mapper.StockWatchlistItemMapper;
import com.jx.tracker.mapper.StockWatchlistMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockWatchlistServiceImplTest {

    @Mock
    private StockWatchlistMapper watchlistMapper;

    @Mock
    private StockWatchlistItemMapper itemMapper;

    @Mock
    private StockBaseMapper stockBaseMapper;

    private StockWatchlistServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StockWatchlistServiceImpl(watchlistMapper, itemMapper, stockBaseMapper);
    }

    @Test
    void exposesWatchlistBusinessFields() {
        assertEquals("my-follow", StockWatchlist.builder().poolCode("my-follow").build().getPoolCode());
        assertEquals("600519.SH", StockWatchlistItem.builder().symbol("600519.SH").build().getSymbol());
    }

    @Test
    void declaresMybatisPlusEntityMappings() throws NoSuchFieldException {
        assertTableMapping(StockWatchlist.class, "stock_watchlist");
        assertTableMapping(StockWatchlistItem.class, "stock_watchlist_item");

        assertAutoId(StockWatchlist.class);
        assertAutoId(StockWatchlistItem.class);

        assertTimeFieldMapping(StockWatchlist.class, "createdTime", "created_at", FieldFill.INSERT);
        assertTimeFieldMapping(StockWatchlist.class, "updatedTime", "updated_at", FieldFill.INSERT_UPDATE);
        assertTimeFieldMapping(StockWatchlistItem.class, "createdTime", "created_at", FieldFill.INSERT);
        assertTimeFieldMapping(StockWatchlistItem.class, "updatedTime", "updated_at", FieldFill.INSERT_UPDATE);
    }

    @Test
    void declaresWatchlistSchemaConstraints() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/db/stock_ai_rule_schema.sql")) {
            assertNotNull(input);
            String ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(ddl.contains("UNIQUE KEY uk_stock_watchlist_pool_code (pool_code)"));
            assertTrue(ddl.contains(
                    "UNIQUE KEY uk_stock_watchlist_item_watchlist_symbol (watchlist_id, symbol)"
            ));
            assertTrue(ddl.contains(
                    "FOREIGN KEY (watchlist_id) REFERENCES stock_watchlist(id) ON DELETE CASCADE"
            ));
        }
    }

    @Test
    void initializesDefaultPoolOnFirstListAndReturnsVirtualAllPool() {
        AtomicReference<StockWatchlist> savedPool = new AtomicReference<>();
        StockBase stock = stock("600519.SH", "贵州茅台", "A股", "白酒");
        when(watchlistMapper.selectOne(any())).thenAnswer(invocation -> savedPool.get());
        when(watchlistMapper.insert((StockWatchlist) any())).thenAnswer(invocation -> {
            StockWatchlist pool = invocation.getArgument(0);
            pool.setId(1L);
            savedPool.set(pool);
            return 1;
        });
        when(watchlistMapper.selectList(any())).thenAnswer(invocation -> List.of(savedPool.get()));
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(stockBaseMapper.selectList(any())).thenReturn(List.of(stock));

        List<StockConsoleVo.WatchlistPool> pools = service.list("A股");

        ArgumentCaptor<StockWatchlist> captor = ArgumentCaptor.forClass(StockWatchlist.class);
        verify(watchlistMapper).insert((StockWatchlist) captor.capture());
        assertThat(captor.getValue())
                .extracting(StockWatchlist::getPoolCode, StockWatchlist::getPoolName,
                        StockWatchlist::getMarket, StockWatchlist::getIsSystem)
                .containsExactly("my-follow", "我的关注", "A股", true);
        assertThat(pools).extracting(StockConsoleVo.WatchlistPool::poolId)
                .containsExactly("my-follow", "all");
        assertThat(pools.get(1).stocks()).singleElement().satisfies(row -> {
            assertThat(row.symbol()).isEqualTo("600519.SH");
            assertThat(row.selected()).isFalse();
        });
    }

    @Test
    void alwaysCreatesDefaultPoolForAShareWhenFirstListingHongKongStocks() {
        AtomicReference<StockWatchlist> savedPool = new AtomicReference<>();
        when(watchlistMapper.selectOne(any())).thenAnswer(invocation -> savedPool.get());
        when(watchlistMapper.insert((StockWatchlist) any())).thenAnswer(invocation -> {
            StockWatchlist pool = invocation.getArgument(0);
            pool.setId(1L);
            savedPool.set(pool);
            return 1;
        });
        when(watchlistMapper.selectList(any())).thenReturn(List.of());
        when(stockBaseMapper.selectList(any())).thenReturn(List.of());

        service.list("港股");

        ArgumentCaptor<StockWatchlist> captor = ArgumentCaptor.forClass(StockWatchlist.class);
        verify(watchlistMapper).insert((StockWatchlist) captor.capture());
        assertThat(captor.getValue().getMarket()).isEqualTo("A股");
    }

    @Test
    void continuesListingWhenAnotherTransactionCreatesDefaultPoolFirst() {
        StockWatchlist defaultPool = pool(1L, "my-follow", "我的关注", "A股", true);
        when(watchlistMapper.selectOne(any())).thenReturn(null, defaultPool);
        when(watchlistMapper.insert((StockWatchlist) any()))
                .thenThrow(new DuplicateKeyException("duplicate pool_code"));
        when(watchlistMapper.selectList(any())).thenReturn(List.of(defaultPool));
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(stockBaseMapper.selectList(any())).thenReturn(List.of());

        List<StockConsoleVo.WatchlistPool> pools = service.list("A股");

        assertThat(pools).extracting(StockConsoleVo.WatchlistPool::poolId)
                .containsExactly("my-follow", "all");
        verify(watchlistMapper, times(2)).selectOne(any());
    }

    @Test
    void listsPersistedPoolMembersWithStockDetailsAndGroup() {
        StockWatchlist pool = pool(7L, "my-growth", "成长池", "A股", false);
        StockWatchlistItem item = StockWatchlistItem.builder()
                .watchlistId(7L)
                .symbol("688981.SH")
                .groupName("半导体")
                .build();
        StockBase stock = stock("688981.SH", "中芯国际", "A股", "半导体");
        when(watchlistMapper.selectOne(any())).thenReturn(pool);
        when(watchlistMapper.selectList(any())).thenReturn(List.of(pool));
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(stockBaseMapper.selectList(any())).thenReturn(List.of(stock));

        List<StockConsoleVo.WatchlistPool> pools = service.list("A股");

        assertThat(pools.get(0).stocks()).singleElement().satisfies(row -> {
            assertThat(row.symbol()).isEqualTo("688981.SH");
            assertThat(row.name()).isEqualTo("中芯国际");
            assertThat(row.market()).isEqualTo("A股");
            assertThat(row.industry()).isEqualTo("半导体");
            assertThat(row.groupName()).isEqualTo("半导体");
            assertThat(row.selected()).isTrue();
        });
    }

    @Test
    void listsAllPoolsWithOneStockQueryAndOneBatchItemQuery() {
        StockWatchlist first = pool(7L, "first", "第一池", "A股", false);
        StockWatchlist second = pool(8L, "second", "第二池", "A股", false);
        when(watchlistMapper.selectOne(any())).thenReturn(first);
        when(watchlistMapper.selectList(any())).thenReturn(List.of(first, second));
        when(itemMapper.selectList(any())).thenReturn(List.of(
                StockWatchlistItem.builder().watchlistId(7L).symbol("sh600519").build(),
                StockWatchlistItem.builder().watchlistId(8L).symbol("000001.SZ").build()
        ));
        when(stockBaseMapper.selectList(any())).thenReturn(List.of(
                stock("600519.SH", "贵州茅台", "A股", "白酒"),
                stock("000001.SZ", "平安银行", "A股", "银行")
        ));

        List<StockConsoleVo.WatchlistPool> pools = service.list("A股");

        assertThat(pools).hasSize(3);
        assertThat(pools.get(0).stocks()).extracting(StockConsoleVo.WatchlistStock::symbol)
                .containsExactly("600519.SH");
        assertThat(pools.get(1).stocks()).extracting(StockConsoleVo.WatchlistStock::symbol)
                .containsExactly("000001.SZ");
        assertThat(pools.get(2).stocks()).hasSize(2);
        verify(itemMapper, times(1)).selectList(any());
        verify(stockBaseMapper, times(1)).selectList(any());
    }

    @Test
    void createsCustomPoolsWithPersistedUniqueCodes() {
        when(watchlistMapper.insert((StockWatchlist) any())).thenAnswer(invocation -> {
            StockWatchlist pool = invocation.getArgument(0);
            pool.setId(10L);
            return 1;
        });
        StockConsoleVo.WatchlistMutationRequest request =
                new StockConsoleVo.WatchlistMutationRequest("成长池", "A股");

        StockConsoleVo.WatchlistPool first = service.create(request);
        StockConsoleVo.WatchlistPool second = service.create(request);

        ArgumentCaptor<StockWatchlist> captor = ArgumentCaptor.forClass(StockWatchlist.class);
        verify(watchlistMapper, org.mockito.Mockito.times(2))
                .insert((StockWatchlist) captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(saved -> {
            assertThat(saved.getPoolCode()).isNotBlank();
            assertThat(saved.getPoolName()).isEqualTo("成长池");
            assertThat(saved.getMarket()).isEqualTo("A股");
            assertThat(saved.getIsSystem()).isFalse();
        });
        assertThat(first.poolId()).isEqualTo(captor.getAllValues().get(0).getPoolCode());
        assertThat(second.poolId()).isEqualTo(captor.getAllValues().get(1).getPoolCode());
        assertThat(first.poolId()).isNotEqualTo(second.poolId());
    }

    @Test
    void rejectsBlankPoolNameAndMarket() {
        assertThatThrownBy(() -> service.create(new StockConsoleVo.WatchlistMutationRequest(" ", "A股")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("名称");
        assertThatThrownBy(() -> service.create(new StockConsoleVo.WatchlistMutationRequest("成长池", " ")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("市场");
        verify(watchlistMapper, never()).insert((StockWatchlist) any());
    }

    @Test
    void updatesCustomPoolByPoolCode() {
        StockWatchlist pool = pool(7L, "my-growth", "成长池", "A股", false);
        when(watchlistMapper.selectOne(any())).thenReturn(pool);
        when(itemMapper.selectCount(any())).thenReturn(0L);
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(watchlistMapper.updateById(pool)).thenReturn(1);

        StockConsoleVo.WatchlistPool result = service.update(
                "my-growth",
                new StockConsoleVo.WatchlistMutationRequest("港股成长", "港股")
        );

        verify(watchlistMapper).updateById(pool);
        assertThat(result.poolName()).isEqualTo("港股成长");
        assertThat(result.market()).isEqualTo("港股");
    }

    @Test
    void rejectsMarketChangeForNonEmptyPoolButAllowsItForEmptyPool() {
        StockWatchlist nonEmpty = pool(7L, "my-growth", "成长池", "A股", false);
        when(watchlistMapper.selectOne(any())).thenReturn(nonEmpty);
        when(itemMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.update(
                " MY-GROWTH ", new StockConsoleVo.WatchlistMutationRequest("港股成长", "港股")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("非空股票池不可修改市场");
        verify(watchlistMapper, never()).updateById((StockWatchlist) any());

        StockWatchlist empty = pool(8L, "empty", "空池", "A股", false);
        when(watchlistMapper.selectOne(any())).thenReturn(empty);
        when(itemMapper.selectCount(any())).thenReturn(0L);
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(watchlistMapper.updateById(empty)).thenReturn(1);

        StockConsoleVo.WatchlistPool result = service.update(
                " EMPTY ", new StockConsoleVo.WatchlistMutationRequest("港股空池", "港股"));

        assertThat(result.market()).isEqualTo("港股");
    }

    @Test
    void locksParentPoolForEveryMemberOrPoolMutation() {
        StockWatchlist pool = pool(7L, "my-growth", "成长池", "A股", false);
        StockBase stock = stock("600519.SH", "贵州茅台", "A股", "白酒");
        when(watchlistMapper.selectOne(any())).thenReturn(pool);
        when(watchlistMapper.updateById((StockWatchlist) any())).thenReturn(1);
        when(watchlistMapper.deleteById(any(Serializable.class))).thenReturn(1);
        when(stockBaseMapper.selectOne(any())).thenReturn(stock);
        when(itemMapper.selectOne(any())).thenReturn(null);
        when(itemMapper.insert((StockWatchlistItem) any())).thenReturn(1);
        when(itemMapper.delete(any())).thenReturn(1);
        when(itemMapper.selectList(any())).thenReturn(List.of());

        service.update("MY-GROWTH", new StockConsoleVo.WatchlistMutationRequest("成长池", "A股"));
        service.delete("MY-GROWTH");
        service.addStock("MY-GROWTH", new StockConsoleVo.WatchlistStockMutationRequest("600519", null));
        service.removeStock("MY-GROWTH", "600519.sh");

        ArgumentCaptor<AbstractWrapper<StockWatchlist, ?, ?>> captor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(watchlistMapper, times(4)).selectOne(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(wrapper ->
                assertThat(ReflectionTestUtils.getField(wrapper, "lastSql").toString())
                        .containsIgnoringCase("FOR UPDATE"));
    }

    @Test
    void protectsSystemAndVirtualPoolsFromDeletion() {
        when(watchlistMapper.selectOne(any())).thenReturn(
                pool(1L, "my-follow", "我的关注", "A股", true),
                pool(2L, "risk-system", "风险系统池", "A股", true)
        );

        assertThatThrownBy(() -> service.delete("MY-FOLLOW"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不可删除");
        assertThatThrownBy(() -> service.delete("RISK-SYSTEM"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不可删除");
        assertThatThrownBy(() -> service.delete("all"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不可删除");
        verify(watchlistMapper, never()).deleteById(any(Serializable.class));
    }

    @Test
    void trimsPoolCodeBeforeProtectingSystemAndVirtualPools() {
        StockConsoleVo.WatchlistMutationRequest poolRequest =
                new StockConsoleVo.WatchlistMutationRequest("股票池", "A股");
        StockConsoleVo.WatchlistStockMutationRequest stockRequest =
                new StockConsoleVo.WatchlistStockMutationRequest("600519.SH", null);

        when(watchlistMapper.selectOne(any())).thenReturn(pool(1L, "my-follow", "我的关注", "A股", true));
        assertThatThrownBy(() -> service.delete(" MY-FOLLOW "))
                .isInstanceOf(ServiceException.class)
                .hasMessage("系统股票池不可删除：my-follow");
        assertThatThrownBy(() -> service.delete(" all "))
                .isInstanceOf(ServiceException.class)
                .hasMessage("系统股票池不可删除：all");
        assertThatThrownBy(() -> service.update(" all ", poolRequest))
                .isInstanceOf(ServiceException.class)
                .hasMessage("全量股票池不可更新");
        assertThatThrownBy(() -> service.addStock(" all ", stockRequest))
                .isInstanceOf(ServiceException.class)
                .hasMessage("全量股票池不可添加股票");
        assertThatThrownBy(() -> service.removeStock(" all ", "600519.SH"))
                .isInstanceOf(ServiceException.class)
                .hasMessage("全量股票池不可移除股票");
        verify(watchlistMapper, times(1)).selectOne(any());
        verifyNoInteractions(itemMapper, stockBaseMapper);
    }

    @Test
    void deletesCustomPoolById() {
        StockWatchlist pool = pool(7L, "my-growth", "成长池", "A股", false);
        when(watchlistMapper.selectOne(any())).thenReturn(pool);
        when(watchlistMapper.deleteById((Serializable) 7L)).thenReturn(1);

        service.delete("my-growth");

        verify(watchlistMapper).deleteById((Serializable) 7L);
    }

    @Test
    void failsWhenConcurrentUpdateOrDeleteAffectsNoRows() {
        StockWatchlist pool = pool(7L, "my-growth", "成长池", "A股", false);
        when(watchlistMapper.selectOne(any())).thenReturn(pool);
        when(watchlistMapper.updateById(pool)).thenReturn(0);

        assertThatThrownBy(() -> service.update(
                "my-growth", new StockConsoleVo.WatchlistMutationRequest("成长价值", "A股")))
                .isInstanceOf(ServiceException.class)
                .hasMessage("股票池状态已变化，请刷新后重试");

        when(watchlistMapper.deleteById((Serializable) 7L)).thenReturn(0);
        assertThatThrownBy(() -> service.delete("my-growth"))
                .isInstanceOf(ServiceException.class)
                .hasMessage("股票池状态已变化，请刷新后重试");
    }

    @Test
    void rejectsDuplicateMember() {
        StockWatchlist pool = pool(7L, "my-growth", "成长池", "A股", false);
        StockBase stock = stock("688981.SH", "中芯国际", "A股", "半导体");
        when(watchlistMapper.selectOne(any())).thenReturn(pool);
        when(stockBaseMapper.selectOne(any())).thenReturn(stock);
        when(itemMapper.selectOne(any())).thenReturn(StockWatchlistItem.builder().id(9L).build());

        assertThatThrownBy(() -> service.addStock(
                "my-growth",
                new StockConsoleVo.WatchlistStockMutationRequest("688981.SH", null)
        )).isInstanceOf(ServiceException.class).hasMessageContaining("已在股票池");
        verify(itemMapper, never()).insert((StockWatchlistItem) any());
    }

    @Test
    void rejectsUnknownStock() {
        when(watchlistMapper.selectOne(any())).thenReturn(
                pool(7L, "my-growth", "成长池", "A股", false)
        );
        when(stockBaseMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.addStock(
                "my-growth",
                new StockConsoleVo.WatchlistStockMutationRequest("UNKNOWN", null)
        )).isInstanceOf(ServiceException.class).hasMessageContaining("股票不存在");
        verify(itemMapper, never()).insert((StockWatchlistItem) any());
    }

    @Test
    void rejectsStockFromDifferentMarket() {
        when(watchlistMapper.selectOne(any())).thenReturn(
                pool(7L, "my-growth", "成长池", "A股", false)
        );
        when(stockBaseMapper.selectOne(any())).thenReturn(
                stock("00700.HK", "腾讯控股", "港股", "互联网")
        );

        assertThatThrownBy(() -> service.addStock(
                "my-growth",
                new StockConsoleVo.WatchlistStockMutationRequest("00700.HK", null)
        )).isInstanceOf(ServiceException.class).hasMessageContaining("市场不一致");
        verify(itemMapper, never()).insert((StockWatchlistItem) any());
    }

    @Test
    void addsStockAndNormalizesBlankGroupNameToNull() {
        StockWatchlist pool = pool(7L, "my-growth", "成长池", "A股", false);
        StockBase stock = stock("688981.SH", "中芯国际", "A股", "半导体");
        AtomicReference<StockWatchlistItem> savedItem = new AtomicReference<>();
        when(watchlistMapper.selectOne(any())).thenReturn(pool);
        when(stockBaseMapper.selectOne(any())).thenReturn(stock);
        when(itemMapper.selectOne(any())).thenReturn(null);
        when(itemMapper.insert((StockWatchlistItem) any())).thenAnswer(invocation -> {
            StockWatchlistItem item = invocation.getArgument(0);
            item.setId(11L);
            savedItem.set(item);
            return 1;
        });
        when(itemMapper.selectList(any())).thenAnswer(invocation -> List.of(savedItem.get()));
        when(stockBaseMapper.selectList(any())).thenReturn(List.of(stock));

        StockConsoleVo.WatchlistPool result = service.addStock(
                "my-growth",
                new StockConsoleVo.WatchlistStockMutationRequest("sh688981", " ")
        );

        ArgumentCaptor<StockWatchlistItem> captor = ArgumentCaptor.forClass(StockWatchlistItem.class);
        verify(itemMapper).insert((StockWatchlistItem) captor.capture());
        assertThat(captor.getValue().getWatchlistId()).isEqualTo(7L);
        assertThat(captor.getValue().getSymbol()).isEqualTo("688981.SH");
        assertThat(captor.getValue().getGroupName()).isNull();
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.stocks()).singleElement().satisfies(row -> {
            assertThat(row.symbol()).isEqualTo("688981.SH");
            assertThat(row.selected()).isTrue();
        });
    }

    @Test
    void normalizesEquivalentSymbolsForAddAndRemoveQueries() {
        initializeTableInfo(StockBase.class);
        initializeTableInfo(StockWatchlistItem.class);
        StockWatchlist pool = pool(7L, "my-growth", "成长池", "A股", false);
        StockBase stock = stock("600519.SH", "贵州茅台", "A股", "白酒");
        when(watchlistMapper.selectOne(any())).thenReturn(pool);
        when(stockBaseMapper.selectOne(any())).thenReturn(stock);
        when(itemMapper.selectOne(any())).thenReturn(null);
        when(itemMapper.insert((StockWatchlistItem) any())).thenReturn(1);
        when(itemMapper.selectList(any())).thenReturn(List.of());
        when(itemMapper.delete(any())).thenReturn(1);

        service.addStock("my-growth", new StockConsoleVo.WatchlistStockMutationRequest("sh600519", null));
        service.removeStock("my-growth", "600519.sh");
        service.removeStock("my-growth", "600519");

        ArgumentCaptor<Wrapper<StockBase>> stockQuery = ArgumentCaptor.forClass(Wrapper.class);
        verify(stockBaseMapper).selectOne(stockQuery.capture());
        stockQuery.getValue().getSqlSegment();
        assertThat(((AbstractWrapper<?, ?, ?>) stockQuery.getValue()).getParamNameValuePairs())
                .containsValue("600519.SH");

        ArgumentCaptor<StockWatchlistItem> item = ArgumentCaptor.forClass(StockWatchlistItem.class);
        verify(itemMapper).insert(item.capture());
        assertThat(item.getValue().getSymbol()).isEqualTo("600519.SH");

        ArgumentCaptor<Wrapper<StockWatchlistItem>> removeQueries = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper, times(2)).delete(removeQueries.capture());
        assertThat(removeQueries.getAllValues()).allSatisfy(query -> {
            query.getSqlSegment();
            assertThat(((AbstractWrapper<?, ?, ?>) query).getParamNameValuePairs())
                    .containsValue("600519.SH");
        });
    }

    @Test
    void convertsConcurrentMemberInsertConflictToServiceException() {
        StockWatchlist pool = pool(7L, "my-growth", "成长池", "A股", false);
        StockBase stock = stock("688981.SH", "中芯国际", "A股", "半导体");
        when(watchlistMapper.selectOne(any())).thenReturn(pool);
        when(stockBaseMapper.selectOne(any())).thenReturn(stock);
        when(itemMapper.selectOne(any())).thenReturn(null);
        when(itemMapper.insert((StockWatchlistItem) any()))
                .thenThrow(new DuplicateKeyException("duplicate watchlist_id and symbol"));

        assertThatThrownBy(() -> service.addStock(
                "my-growth",
                new StockConsoleVo.WatchlistStockMutationRequest("688981.SH", null)
        )).isInstanceOf(ServiceException.class)
                .hasMessage("股票已在股票池中：688981.SH");
    }

    @Test
    void convertsOtherIntegrityConflictsToStaleStateServiceException() {
        StockWatchlist pool = pool(7L, "my-growth", "成长池", "A股", false);
        StockBase stock = stock("688981.SH", "中芯国际", "A股", "半导体");
        when(watchlistMapper.selectOne(any())).thenReturn(pool);
        when(stockBaseMapper.selectOne(any())).thenReturn(stock);
        when(itemMapper.selectOne(any())).thenReturn(null);
        when(itemMapper.insert((StockWatchlistItem) any()))
                .thenThrow(new DataIntegrityViolationException("parent changed"));

        assertThatThrownBy(() -> service.addStock(
                "my-growth", new StockConsoleVo.WatchlistStockMutationRequest("688981.SH", null)))
                .isInstanceOf(ServiceException.class)
                .hasMessage("股票池状态已变化，请刷新后重试");
    }

    @Test
    void removesStockByWatchlistAndSymbol() {
        StockWatchlist pool = pool(7L, "my-growth", "成长池", "A股", false);
        when(watchlistMapper.selectOne(any())).thenReturn(pool);
        when(itemMapper.delete(any())).thenReturn(1);
        when(itemMapper.selectList(any())).thenReturn(List.of());

        StockConsoleVo.WatchlistPool result = service.removeStock("my-growth", "688981.SH");

        verify(itemMapper).delete(any());
        assertThat(result.total()).isZero();
        assertThat(result.stocks()).isEmpty();
    }

    @Test
    void marksEveryOperationThatCanWriteAsTransactional() throws NoSuchMethodException {
        assertTransactional("list", String.class);
        assertTransactional("create", StockConsoleVo.WatchlistMutationRequest.class);
        assertTransactional("update", String.class, StockConsoleVo.WatchlistMutationRequest.class);
        assertTransactional("delete", String.class);
        assertTransactional("addStock", String.class, StockConsoleVo.WatchlistStockMutationRequest.class);
        assertTransactional("removeStock", String.class, String.class);
    }

    @Test
    void listsWithReadCommittedIsolationForConcurrentDefaultPoolInitialization()
            throws NoSuchMethodException {
        Transactional transactional = StockWatchlistServiceImpl.class
                .getMethod("list", String.class)
                .getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertEquals(Isolation.READ_COMMITTED, transactional.isolation());
    }

    private static StockWatchlist pool(Long id, String code, String name, String market, boolean system) {
        return StockWatchlist.builder()
                .id(id)
                .poolCode(code)
                .poolName(name)
                .market(market)
                .isSystem(system)
                .build();
    }

    private static StockBase stock(String symbol, String name, String market, String industry) {
        return StockBase.builder()
                .symbol(symbol)
                .name(name)
                .market(market)
                .industry(industry)
                .build();
    }

    private static void initializeTableInfo(Class<?> entityType) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                entityType
        );
    }

    private static void assertTransactional(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        assertNotNull(StockWatchlistServiceImpl.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(Transactional.class));
    }

    private static void assertTableMapping(Class<?> entityType, String expectedTableName) {
        TableName tableName = entityType.getAnnotation(TableName.class);
        assertNotNull(tableName);
        assertEquals(expectedTableName, tableName.value());
    }

    private static void assertAutoId(Class<?> entityType) throws NoSuchFieldException {
        TableId tableId = entityType.getDeclaredField("id").getAnnotation(TableId.class);
        assertNotNull(tableId);
        assertEquals(IdType.AUTO, tableId.type());
    }

    private static void assertTimeFieldMapping(Class<?> entityType,
                                               String fieldName,
                                               String expectedColumn,
                                               FieldFill expectedFill) throws NoSuchFieldException {
        TableField tableField = entityType.getDeclaredField(fieldName).getAnnotation(TableField.class);
        assertNotNull(tableField);
        assertEquals(expectedColumn, tableField.value());
        assertEquals(expectedFill, tableField.fill());
    }
}
