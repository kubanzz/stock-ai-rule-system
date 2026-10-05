package com.jx.tracker.rule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.dto.RuleGroupDetailDto;
import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.domain.dto.RuleStrategyGroupDto;
import com.jx.tracker.domain.entity.RuleStrategy;
import com.jx.tracker.domain.entity.RuleStrategyVersion;
import com.jx.tracker.domain.entity.StockWatchlist;
import com.jx.tracker.domain.entity.StockWatchlistItem;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.RuleStrategyMapper;
import com.jx.tracker.mapper.RuleStrategyVersionMapper;
import com.jx.tracker.mapper.StockWatchlistItemMapper;
import com.jx.tracker.mapper.StockWatchlistMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RuleStrategyStockPoolTest {
    private final RuleStrategyMapper strategies = mock(RuleStrategyMapper.class);
    private final RuleStrategyVersionMapper versions = mock(RuleStrategyVersionMapper.class);
    private final RuleGroupService groups = mock(RuleGroupService.class);
    private final StockWatchlistMapper pools = mock(StockWatchlistMapper.class);
    private final StockWatchlistItemMapper members = mock(StockWatchlistItemMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final RuleStrategyService service = new RuleStrategyService(strategies, versions, groups,
            mock(RuleDefinitionMapper.class), json, null, pools, members);

    @BeforeEach
    void configurePersistence() {
        RuleGroupDetailDto group = new RuleGroupDetailDto();
        group.setGroupCode("G_TEST");
        group.setStatus("active");
        group.setVersion("v1");
        group.setMembers(List.of());
        when(groups.getGroup("G_TEST")).thenReturn(group);
        when(strategies.insert(any(RuleStrategy.class))).thenAnswer(invocation -> {
            RuleStrategy row = invocation.getArgument(0);
            row.setId(1L);
            return 1;
        });
        when(strategies.updateById(any(RuleStrategy.class))).thenReturn(1);
        when(versions.insert(any(RuleStrategyVersion.class))).thenReturn(1);
    }

    @Test
    void savesDatabaseMembersAndNameInsteadOfClientSuppliedSnapshot() throws Exception {
        databasePool("真实分组", "000002.SZ", "000001.SZ", "sz000001");
        RuleStrategyDetailDto request = scopedRequest();
        request.setStockPoolName("伪造名称");
        request.setStockPoolSymbols(List.of("600000.SH"));

        RuleStrategyDetailDto result = service.create(request);

        assertThat(result.getStockPoolType()).isEqualTo("watchlist");
        assertThat(result.getStockPoolCode()).isEqualTo("pool-sz125");
        assertThat(result.getStockPoolName()).isEqualTo("真实分组");
        assertThat(result.getStockPoolSymbols()).containsExactly("000001.SZ", "000002.SZ");
        ArgumentCaptor<RuleStrategyVersion> saved = ArgumentCaptor.forClass(RuleStrategyVersion.class);
        verify(versions).insert(saved.capture());
        RuleStrategyDetailDto historical = json.readValue(saved.getValue().getSnapshotJson(),
                RuleStrategyDetailDto.class);
        assertThat(historical.getStockPoolSymbols()).isEqualTo(result.getStockPoolSymbols());
        assertThat(historical.getStockPoolName()).isEqualTo("真实分组");
    }

    @Test
    void refusesMissingOrEmptyStockPools() {
        assertThatThrownBy(() -> service.create(scopedRequest()))
                .isInstanceOf(ServiceException.class).hasMessageContaining("股票分组不存在");
        databasePool("空分组");
        assertThatThrownBy(() -> service.create(scopedRequest()))
                .isInstanceOf(ServiceException.class).hasMessageContaining("股票分组为空");
    }

    @Test
    void refusesMissingPoolCodeAndUnknownScopeType() {
        RuleStrategyDetailDto missingCode = scopedRequest();
        missingCode.setStockPoolCode(" ");
        assertThatThrownBy(() -> service.create(missingCode))
                .isInstanceOf(ServiceException.class).hasMessageContaining("请选择");
        RuleStrategyDetailDto unknownType = request();
        unknownType.setStockPoolType("unknown");
        assertThatThrownBy(() -> service.create(unknownType))
                .isInstanceOf(ServiceException.class).hasMessageContaining("仅支持 all、watchlist");
    }

    @Test
    void omittedScopeOnUpdatePreservesExistingSnapshotDespiteForgedFields() throws Exception {
        RuleStrategy row = existingScopedRow();
        RuleStrategyDetailDto request = request();
        request.setStockPoolCode("another-pool");
        request.setStockPoolSymbols(List.of("600000.SH"));

        RuleStrategyDetailDto result = service.update("S_TEST", request);

        assertThat(result.getStockPoolCode()).isEqualTo("pool-sz125");
        assertThat(result.getStockPoolSymbols()).containsExactly("000001.SZ");
        assertThat(row.getVersion()).isEqualTo("v2");
        verifyNoInteractions(pools, members);
    }

    @Test
    void explicitSaveRefreshesPoolButDoesNotRewriteOldVersion() throws Exception {
        RuleStrategy row = existingScopedRow();
        String oldSnapshot = row.getSnapshotJson();
        databasePool("修改后分组", "000002.SZ");

        RuleStrategyDetailDto result = service.update("S_TEST", scopedRequest());

        assertThat(result.getStockPoolSymbols()).containsExactly("000002.SZ");
        assertThat(result.getStockPoolName()).isEqualTo("修改后分组");
        assertThat(result.getVersion()).isEqualTo("v2");
        RuleStrategyVersion oldVersion = new RuleStrategyVersion();
        oldVersion.setSnapshotJson(oldSnapshot);
        when(versions.selectOne(any())).thenReturn(oldVersion);
        assertThat(service.getVersion("S_TEST", "v1").getStockPoolSymbols())
                .containsExactly("000001.SZ");
    }

    @Test
    void statusChangesRetainScopeEvenWhenSourcePoolWasDeletedOrChanged() throws Exception {
        existingScopedRow();

        RuleStrategyDetailDto enabled = service.changeStatus("S_TEST", "active");
        RuleStrategyDetailDto disabled = service.changeStatus("S_TEST", "disabled");

        assertThat(enabled.getStockPoolSymbols()).containsExactly("000001.SZ");
        assertThat(disabled.getStockPoolSymbols()).containsExactly("000001.SZ");
        verifyNoInteractions(pools, members);
    }

    @Test
    void copyRetainsExactStockScopeWithoutRefreshingSourcePool() throws Exception {
        existingScopedRow();

        RuleStrategyDetailDto copy = service.copy("S_TEST", "S_COPY", "复制方案");

        assertThat(copy.getStockPoolSymbols()).containsExactly("000001.SZ");
        assertThat(copy.getStockPoolName()).isEqualTo("原分组");
        assertThat(copy.getVersion()).isEqualTo("v1");
        assertThat(copy.getStatus()).isEqualTo("draft");
        verifyNoInteractions(pools, members);
    }

    @Test
    void explicitAllClearsBindingAndNewLegacyRequestsDefaultToAll() throws Exception {
        existingScopedRow();
        RuleStrategyDetailDto allRequest = scopedRequest();
        allRequest.setStockPoolType("all");
        allRequest.setStockPoolName("不应保留");
        allRequest.setStockPoolSymbols(List.of("000001.SZ"));

        RuleStrategyDetailDto all = service.update("S_TEST", allRequest);

        assertThat(all.getStockPoolType()).isEqualTo("all");
        assertThat(all.getStockPoolCode()).isNull();
        assertThat(all.getStockPoolName()).isNull();
        assertThat(all.getStockPoolSymbols()).isEmpty();
        RuleStrategyDetailDto created = service.create(request());
        assertThat(created.getStockPoolType()).isEqualTo("all");
        assertThat(created.getStockPoolSymbols()).isEmpty();
        verifyNoInteractions(pools, members);
    }

    @Test
    void readsLegacySnapshotAsAllWithoutRewritingStoredJson() throws Exception {
        RuleStrategy row = row(request());
        row.setSnapshotJson(row.getSnapshotJson().replace("\"stockPoolType\":null,", "")
                .replace("\"stockPoolSymbols\":null,", ""));
        String stored = row.getSnapshotJson();
        when(strategies.selectOne(any())).thenReturn(row);

        RuleStrategyDetailDto legacy = service.getStrategy("S_TEST");

        assertThat(legacy.getStockPoolType()).isEqualTo("all");
        assertThat(legacy.getStockPoolSymbols()).isEmpty();
        assertThat(row.getSnapshotJson()).isEqualTo(stored);
        verifyNoInteractions(pools, members);
    }

    private void databasePool(String name, String... symbols) {
        when(pools.selectOne(any())).thenReturn(StockWatchlist.builder()
                .id(7L).poolCode("pool-sz125").poolName(name).build());
        when(members.selectList(any())).thenReturn(java.util.Arrays.stream(symbols)
                .map(symbol -> StockWatchlistItem.builder().watchlistId(7L).symbol(symbol).build()).toList());
    }

    private RuleStrategy existingScopedRow() throws Exception {
        RuleStrategyDetailDto detail = scopedRequest();
        detail.setStockPoolName("原分组");
        detail.setStockPoolSymbols(List.of("000001.SZ"));
        RuleStrategy row = row(detail);
        when(strategies.selectOne(any())).thenReturn(row);
        return row;
    }

    private RuleStrategy row(RuleStrategyDetailDto detail) throws Exception {
        detail.setVersion("v1");
        RuleStrategy row = new RuleStrategy();
        row.setId(1L);
        row.setStrategyCode("S_TEST");
        row.setVersion("v1");
        row.setStatus("draft");
        row.setSnapshotJson(json.writeValueAsString(detail));
        return row;
    }

    private RuleStrategyDetailDto scopedRequest() {
        RuleStrategyDetailDto request = request();
        request.setStockPoolType("watchlist");
        request.setStockPoolCode("pool-sz125");
        return request;
    }

    private RuleStrategyDetailDto request() {
        RuleStrategyDetailDto request = new RuleStrategyDetailDto();
        request.setStrategyCode("S_TEST");
        request.setStrategyName("测试方案");
        request.setStatus("draft");
        RuleStrategyGroupDto selected = new RuleStrategyGroupDto();
        selected.setGroupCode("G_TEST");
        request.setGroups(List.of(selected));
        return request;
    }
}
