# 信号看板真实 A 股行情接入实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 使用免 Token 的 AKTools/AKShare 同步真实 A 股收盘日线，让股票抽屉可分页搜索全量 A 股，并让无正式信号的股票以“待生成信号”立即出现在看板。

**架构：** AKTools 作为内部 HTTP 采集服务，Spring Boot 通过新的 `AkToolsMarketDataProvider` 映射并持久化本地行情。股票候选使用服务端分页；看板以股票池成员或全市场股票为候选集，左连接目标交易日行情和信号。Vue 只访问本地 Spring Boot API，不在页面加载时直连公开行情源。

**技术栈：** JDK 21、Spring Boot 3.5.15、MyBatis-Plus、MySQL、RestClient、JUnit 5、Vue 3、TypeScript、Ant Design Vue、Vitest、Python 3.12、AKTools、AKShare、Docker Compose。

---

## 文件结构

### 后端创建

- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/provider/AkToolsMarketDataProvider.java`：调用 AKTools 并映射 A 股列表、全市场快照、沪深 300和交易日历。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/provider/AkToolsResponse.java`：统一解析 AKTools 返回的表格行。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/dto/WatchlistCandidateQueryDto.java`：股票候选分页搜索参数。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/service/MarketDataBootstrapService.java`：初始化任务入口。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/service/impl/MarketDataBootstrapServiceImpl.java`：按依赖顺序启动初始化同步。
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/market/data/AkToolsMarketDataProviderTest.java`：AKTools 映射、空响应和指数路由测试。
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/service/impl/StockWatchlistCandidatePersistenceTest.java`：候选分页和批量添加持久化测试。

### 后端修改

- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/provider/MarketDataProviderProperties.java`：增加 AKTools 地址、超时、重试和质量阈值。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/provider/MarketDataProviderResolver.java`：注册 `aktools`，禁止真实源失败后静默落到 mock。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/provider/MarketDataProvider.java`：允许空股票代码表示全市场快照。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/service/impl/MarketDataSyncServiceImpl.java`：接收全市场快照并拒绝空真实数据。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/controller/MarketDataController.java`：增加初始化接口。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/domain/vo/StockConsoleVo.java`：增加候选、批量添加结果、`signalStatus` 和 `quoteStatus`。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/StockWatchlistService.java`：增加候选搜索和批量添加契约。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/impl/StockWatchlistServiceImpl.java`：实现分页候选和批量写入。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/controller/StockConsoleController.java`：暴露候选和批量添加接口。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/impl/StockDashboardQueryServiceImpl.java`：以候选股票为主集合并生成 pending 行。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/impl/StockMarketContextServiceImpl.java`：指数/行业使用全市场口径，信号情绪使用股票池口径。
- `stock-ai-rule-system-service/src/main/resources/application.yml`：增加 AKTools 和每日调度的环境变量配置。
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/market/data/MarketDataProviderResolverTest.java`：增加 AKTools 选择测试。
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/market/data/MarketDataSyncServiceTest.java`：增加空真实数据拒绝和全市场快照测试。
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/service/impl/StockDashboardQueryPersistenceTest.java`：增加 pending 行与指标口径测试。
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/controller/StockConsoleControllerTest.java`：增加候选和批量接口契约测试。

### 前端修改

- `stock-ai-rule-system-ui/apps/web-antd/src/api/stock/types.ts`：增加 pending、候选分页和批量添加类型。
- `stock-ai-rule-system-ui/apps/web-antd/src/api/stock/index.ts`：修正基础路径，并增加候选和批量接口。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/components/stock-picker-drawer.vue`：改为服务端搜索和批量添加。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/components/signal-table.vue`：展示待生成信号和行情待同步。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/index.vue`：添加后切换目标股票池、清除冲突筛选并刷新。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/dashboard-state.ts`：允许 `pending` 信号筛选。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/dashboard-state.test.ts`：验证 pending 筛选和添加后重置。
- `stock-ai-rule-system-ui/apps/web-antd/src/stock-real-backend-contract.test.ts`：验证 API 路径、候选接口和 UI 契约。

### 部署创建

- `infra/aktools/Dockerfile`：固定 Python 与依赖版本并启动 8090 端口。
- `infra/aktools/requirements.txt`：固定 `aktools`、`akshare` 版本。
- `docker-compose.market-data.yml`：启动仅内部可访问的 AKTools 服务。

## 任务 1：修正前端 API 基础路径

**文件：**
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/api/stock/index.ts`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/stock-real-backend-contract.test.ts`

- [ ] **步骤 1：编写失败的路径契约测试**

在契约测试中断言请求路径从 `/signals/dashboard` 开始，并禁止 `baseRequestClient` 再传入 `/api/` 前缀：

```ts
expect(stockApi).toContain("'/signals/dashboard'");
expect(stockApi).toContain("'/watchlists'");
expect(stockApi).not.toMatch(
  /baseRequestClient\.(?:delete|get|post|put)<[\s\S]*?>\(\s*['`]\/api\//,
);
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
pnpm exec vitest run apps/web-antd/src/stock-real-backend-contract.test.ts
```

预期：FAIL，报告仍包含 `/api/signals/dashboard`。

- [ ] **步骤 3：最小修改全部股票 API 路径**

把 `baseRequestClient` 的股票模块路径统一改为不含基础 `/api` 的页面级路径，例如：

```ts
baseRequestClient.get('/signals/dashboard', { params });
baseRequestClient.get('/watchlists', { params });
baseRequestClient.post(`/watchlists/${poolId}/stocks`, data);
```

- [ ] **步骤 4：运行契约测试与类型检查**

运行：

```bash
pnpm exec vitest run apps/web-antd/src/stock-real-backend-contract.test.ts
pnpm --filter @vben/web-antd run typecheck
```

预期：全部 PASS。

- [ ] **步骤 5：提交前端路径修复**

```bash
git add apps/web-antd/src/api/stock/index.ts apps/web-antd/src/stock-real-backend-contract.test.ts
git commit -m "fix: 修正股票接口基础路径"
```

## 任务 2：实现 AKTools Provider 与部署配置

**文件：**
- 创建：`infra/aktools/Dockerfile`
- 创建：`infra/aktools/requirements.txt`
- 创建：`docker-compose.market-data.yml`
- 创建：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/provider/AkToolsMarketDataProvider.java`
- 创建：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/market/data/AkToolsMarketDataProviderTest.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/provider/MarketDataProviderProperties.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/provider/MarketDataProviderResolver.java`
- 修改：`stock-ai-rule-system-service/src/main/resources/application.yml`

- [ ] **步骤 1：编写失败的 Provider 映射测试**

使用 `MockRestServiceServer` 返回 AKTools 表格 JSON，验证 `600519` 被映射为 `600519.SH`，成交量和金额保持系统现有单位，数据源为 `aktools/akshare`：

```java
assertThat(provider.fetchStockList()).singleElement().satisfies(stock -> {
    assertThat(stock.getSymbol()).isEqualTo("600519.SH");
    assertThat(stock.getMarket()).isEqualTo("CN");
    assertThat(stock.getName()).isEqualTo("贵州茅台");
    assertThat(stock.getDataSource()).isEqualTo("aktools/akshare");
});
```

再验证 `fetchDailyQuotes(null, date, date)` 返回全市场快照，`fetchDailyQuotes("000300.SH", start, end)` 路由到指数历史接口，空 `data` 抛出 `ServiceException`。

- [ ] **步骤 2：运行测试验证类不存在**

运行：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=AkToolsMarketDataProviderTest test
```

预期：测试编译失败，`AkToolsMarketDataProvider` 不存在。

- [ ] **步骤 3：实现配置与 Provider**

属性至少包含：

```java
private String baseUrl = "http://127.0.0.1:8090";
private Duration connectTimeout = Duration.ofSeconds(5);
private Duration readTimeout = Duration.ofSeconds(60);
private int retryCount = 3;
private int minimumStockCount = 4000;
private BigDecimal minimumQuoteCoverage = new BigDecimal("0.95");
```

Provider 使用以下 AKTools 函数：

```text
/api/public/stock_zh_a_spot_em
/api/public/stock_zh_index_daily_em?symbol=sh000300
/api/public/tool_trade_date_hist_sina
```

只接受 A 股市场；其他市场抛出“当前 Provider 尚未支持该市场”。外部空响应、非 2xx 或字段缺失统一抛出 `ServiceException`，不得返回 mock 行。

- [ ] **步骤 4：注册 `aktools` 且禁止静默 mock 回退**

Resolver 增加：

```java
case "aktools" -> new MarketDataProviderSelection(
    akToolsMarketDataProvider, "aktools/akshare", false, null
);
```

只有显式 `type=mock` 时才选择 mock。`aktools` 初始化失败应保留 `aktools/akshare` 失败状态，不构造 mock selection。

- [ ] **步骤 5：增加固定版本部署文件**

`requirements.txt` 固定已验证版本；Dockerfile 运行：

```dockerfile
CMD ["python", "-m", "aktools", "--host", "0.0.0.0", "--port", "8090"]
```

Compose 仅暴露到 `127.0.0.1:8090`，Spring 配置通过 `AKTOOLS_BASE_URL` 覆盖地址。

- [ ] **步骤 6：运行 Provider 和 Resolver 测试**

运行：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=AkToolsMarketDataProviderTest,MarketDataProviderResolverTest test
```

预期：全部 PASS。

- [ ] **步骤 7：提交 AKTools Provider**

```bash
git add infra docker-compose.market-data.yml stock-ai-rule-system-service/src/main stock-ai-rule-system-service/src/test/java/com/jx/tracker/market/data
git commit -m "feat: 接入免 Token A 股行情 Provider"
```

## 任务 3：支持全市场同步与初始化任务

**文件：**
- 创建：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/service/MarketDataBootstrapService.java`
- 创建：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/service/impl/MarketDataBootstrapServiceImpl.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/service/impl/MarketDataSyncServiceImpl.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/controller/MarketDataController.java`
- 修改：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/market/data/MarketDataSyncServiceTest.java`
- 修改：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/market/data/MarketDataControllerTest.java`

- [ ] **步骤 1：编写失败的全市场同步测试**

设置 `targetSymbol=null`，Provider 返回两条快照，断言两条都传入 `upsertDailyQuotes`；Provider 返回空列表时断言同步运行状态为 `failed`，且不会调用写服务。

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=MarketDataSyncServiceTest test
```

预期：空列表当前被当作成功，测试 FAIL。

- [ ] **步骤 3：实现真实源非空与覆盖率校验**

`syncStockList` 和全市场 `syncDailyQuotes` 在写事务前验证行数；低于阈值或覆盖率不足抛出 `ServiceException`。测试用 Provider 使用较低阈值，生产默认阈值使用任务 2 的配置。

- [ ] **步骤 4：实现初始化任务**

定义：

```java
public interface MarketDataBootstrapService {
    BootstrapAccepted start(String market, String triggerBy);
}

public record BootstrapAccepted(String jobId, String market, String status) {}
```

后台按“股票列表 → 交易日历 → 全市场快照 → 沪深 300 最近 60 个交易日”调用现有同步服务。每一步继续使用 `market_data_sync_run` 留痕；任一步失败后停止依赖步骤。

- [ ] **步骤 5：增加初始化 Controller 契约测试与接口**

```java
@PostMapping("/sync/bootstrap")
public AjaxResult bootstrap(@RequestParam(defaultValue = "A股") String market) {
    return AjaxResult.success(marketDataBootstrapService.start(market, "manual"));
}
```

测试断言 HTTP 200、返回 `jobId`、`market=A股`、`status=accepted`。

- [ ] **步骤 6：运行同步相关测试**

运行：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=MarketDataSyncServiceTest,MarketDataControllerTest test
```

预期：全部 PASS。

- [ ] **步骤 7：提交同步与初始化任务**

```bash
git add stock-ai-rule-system-service/src/main stock-ai-rule-system-service/src/test/java/com/jx/tracker/market/data
git commit -m "feat: 增加 A 股全市场初始化同步"
```

## 任务 4：实现股票候选分页与批量添加

**文件：**
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/domain/vo/StockConsoleVo.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/StockWatchlistService.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/impl/StockWatchlistServiceImpl.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/controller/StockConsoleController.java`
- 创建：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/service/impl/StockWatchlistCandidatePersistenceTest.java`
- 修改：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/controller/StockConsoleControllerTest.java`

- [ ] **步骤 1：编写失败的候选分页持久化测试**

插入贵州茅台、宁德时代和两条无关股票，把贵州茅台加入目标池，查询关键字 `茅台` 后断言：

```java
assertThat(result.getTotal()).isEqualTo(1);
assertThat(result.getRows()).singleElement().satisfies(row -> {
    assertThat(row.symbol()).isEqualTo("600519.SH");
    assertThat(row.inPool()).isTrue();
});
```

查询纯数字 `300750` 能命中 `300750.SZ`。

- [ ] **步骤 2：运行候选测试验证接口不存在**

运行：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=StockWatchlistCandidatePersistenceTest test
```

预期：测试编译失败，候选契约不存在。

- [ ] **步骤 3：实现候选 DTO 与分页查询**

定义：

```java
public record WatchlistCandidate(
    String symbol, String name, String market, String exchange,
    String industry, boolean inPool
) {}
```

查询在数据库侧按市场、状态和 `symbol/name LIKE` 分页；`inPool` 依据目标股票池成员集合生成。页大小限制为 100。

- [ ] **步骤 4：编写失败的批量添加测试**

请求包含 `600519.SH`、`300750.SZ`、重复的 `600519.SH`，断言新增两只、重复输入只处理一次；再次提交时两只都进入 `skippedSymbols`，数据库没有重复行。

- [ ] **步骤 5：实现批量添加**

定义：

```java
public record WatchlistBatchMutationRequest(
    @Size(min = 1, max = 100) List<@NotBlank String> symbols,
    @Size(max = 64) String groupName
) {}

public record WatchlistBatchMutationResult(
    String poolCode, List<String> addedSymbols,
    List<String> skippedSymbols, List<String> failedSymbols
) {}
```

同一事务内锁定目标股票池，校验市场，批量去重并写入；已存在成员进入 skipped，不回滚其他合法成员。

- [ ] **步骤 6：增加 Controller 接口与契约测试**

```java
@GetMapping("/watchlists/{poolId}/stock-candidates")
@PostMapping("/watchlists/{poolId}/stocks/batch")
```

断言分页返回 `rows/total`，批量接口返回 `addedSymbols/skippedSymbols/failedSymbols`。

- [ ] **步骤 7：运行股票池相关测试**

运行：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=StockWatchlistCandidatePersistenceTest,StockWatchlistServiceImplTest,StockWatchlistPersistenceTest,StockConsoleControllerTest test
```

预期：全部 PASS。

- [ ] **步骤 8：提交股票候选与批量添加**

```bash
git add stock-ai-rule-system-service/src/main stock-ai-rule-system-service/src/test
git commit -m "feat: 支持真实股票分页搜索与批量关注"
```

## 任务 5：让无信号股票进入看板

**文件：**
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/domain/vo/StockConsoleVo.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/impl/StockDashboardQueryServiceImpl.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/impl/StockMarketContextServiceImpl.java`
- 修改：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/service/impl/StockDashboardQueryPersistenceTest.java`
- 修改：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/service/impl/StockDashboardQueryServiceImplTest.java`

- [ ] **步骤 1：编写失败的 pending 看板测试**

股票池包含两只股票，只有一只有信号，两只都有行情。断言看板返回两行：

```java
assertThat(result.signals()).extracting(StockConsoleVo.SignalRow::signalStatus)
    .containsExactlyInAnyOrder("ready", "pending");
assertThat(result.total()).isEqualTo(2);
```

指标断言“关注股票=2”“产生信号=1”。

- [ ] **步骤 2：运行测试验证 pending 行缺失**

运行：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=StockDashboardQueryPersistenceTest test
```

预期：FAIL，当前只返回一条正式信号。

- [ ] **步骤 3：扩展行契约**

`SignalRow` 增加：

```java
String signalStatus, // ready | pending
String quoteStatus   // ready | pending
```

无信号行的信号分数、置信度和建议周期为 `null`，规则数为 `0`。

- [ ] **步骤 4：实现候选集左连接**

交易日解析优先级为“显式日期 → 候选股票最新行情日期 → 最新信号日期”。批量读取候选股票同日行情和信号，按股票生成行；只有指定正式信号筛选时才剔除 pending，`signal=pending` 时只保留 pending。

- [ ] **步骤 5：修正市场环境口径**

沪深 300和行业强弱使用当前市场全部股票；信号情绪和风险使用当前股票池候选。基准查询与股票池是否为空无关。

- [ ] **步骤 6：运行看板相关测试**

运行：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=StockDashboardQueryPersistenceTest,StockDashboardQueryServiceImplTest test
```

预期：全部 PASS。

- [ ] **步骤 7：提交 pending 看板实现**

```bash
git add stock-ai-rule-system-service/src/main stock-ai-rule-system-service/src/test
git commit -m "fix: 在信号看板展示待生成股票"
```

## 任务 6：前端接入分页候选和 pending 状态

**文件：**
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/api/stock/types.ts`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/api/stock/index.ts`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/components/stock-picker-drawer.vue`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/components/signal-table.vue`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/index.vue`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/dashboard-state.ts`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/dashboard-state.test.ts`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/stock-real-backend-contract.test.ts`

- [ ] **步骤 1：编写失败的前端契约测试**

断言股票抽屉调用 `getWatchlistStockCandidates` 和 `addWatchlistStocksBatch`，不再从 `props.pools` 的 `all.stocks` 计算候选；断言信号表包含“待生成信号”和“行情待同步”。

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
pnpm exec vitest run apps/web-antd/src/stock-real-backend-contract.test.ts apps/web-antd/src/views/stock/signals/dashboard-state.test.ts
```

预期：FAIL，两个新 API 和 pending 文案不存在。

- [ ] **步骤 3：增加前端类型与 API**

```ts
export type DashboardSignalFilter = SignalType | 'pending';
export type SignalStatus = 'pending' | 'ready';
export interface WatchlistCandidate extends WatchlistStock {
  exchange?: string;
  inPool: boolean;
}
```

增加分页候选 GET 和批量添加 POST，返回后端 `rows/total` 与批量结果。

- [ ] **步骤 4：改造股票抽屉**

打开抽屉加载第一页；输入内容使用 300ms 防抖；分页切换请求后端；`inPool` 行禁用；确认按钮只调用一次批量接口。成功事件发送：

```ts
emit('changed', {
  poolCode: targetPoolId.value,
  addedSymbols: result.addedSymbols,
});
```

- [ ] **步骤 5：添加后切换股票池并刷新**

父页面收到事件后设置目标 `poolCode`、清空 `symbol/signal/confidence` 筛选、回到第一页并同时刷新股票池和看板。

- [ ] **步骤 6：展示 pending 与行情状态**

`signalStatus=pending` 时标签显示“待生成信号”，分数显示 `--`；`quoteStatus=pending` 时价格列显示“行情待同步”。信号下拉增加“待生成信号”。

- [ ] **步骤 7：运行前端测试与类型检查**

运行：

```bash
pnpm exec vitest run apps/web-antd/src/stock-real-backend-contract.test.ts apps/web-antd/src/views/stock/signals/dashboard-state.test.ts
pnpm --filter @vben/web-antd run typecheck
```

预期：全部 PASS。

- [ ] **步骤 8：提交前端功能**

```bash
git add apps/web-antd/src/api/stock apps/web-antd/src/views/stock/signals apps/web-antd/src/stock-real-backend-contract.test.ts
git commit -m "feat: 接入真实股票搜索与待生成状态"
```

## 任务 7：全量验证与运行说明

**文件：**
- 修改：`股票因子规则预测与 AI 规则优化系统设计方案.md`
- 修改：`docs/superpowers/plans/2026-07-12-signal-dashboard-real-market-data-plan.md`

- [x] **步骤 1：启动并验证 AKTools 健康状态**

运行：

```bash
docker-compose -f docker-compose.market-data.yml up -d --build
curl --fail http://127.0.0.1:8090/version
```

预期：AKTools 返回成功响应。

- [x] **步骤 2：运行后端全量测试**

```bash
cd stock-ai-rule-system-service
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test
```

预期：`BUILD SUCCESS`，0 failures，0 errors。

- [x] **步骤 3：运行前端验证**

```bash
cd stock-ai-rule-system-ui
pnpm exec vitest run apps/web-antd/src/stock-real-backend-contract.test.ts apps/web-antd/src/views/stock/signals/dashboard-state.test.ts
pnpm --filter @vben/web-antd run typecheck
```

预期：相关测试和类型检查全部通过。

- [x] **步骤 4：执行真实数据验收**

启动后端后调用初始化接口，轮询同步运行记录；验收数据库基础股票超过配置下限、`600519.SH` 与 `300750.SZ` 可搜索、沪深 300存在至少 20 个交易日、添加后返回 pending 行。

- [x] **步骤 5：更新设计方案的运行说明**

记录 AKTools 启动方式、所需环境变量、初始化接口、18:00 收盘任务、失败时不回退 mock，以及所有信号均为辅助决策信息。

- [x] **步骤 6：自检工作区并提交文档**

```bash
git diff --check
git status --short
git add '股票因子规则预测与 AI 规则优化系统设计方案.md' docs/superpowers/plans/2026-07-12-signal-dashboard-real-market-data-plan.md
git commit -m "docs: 补充真实行情运行与验收说明"
```

预期：只提交本功能文档，不包含日志、密钥或用户已有改动。

### 实际验收记录（2026-07-12）

- AKTools `/version` 返回 AKTools `0.0.91`、AKShare `1.18.64`；
- 新浪公开全 A 股快照返回 `5529` 行，包含 `600519`、`300750`；
- `stock_base` 共 `5530` 行，2026-07-10 有效收盘快照 `5526` 行；
- 沪深 300落库 `82` 个交易日，看板返回最近 `20` 个走势点；
- 通过批量接口加入 `600519.SH` 后，`my-follow` 看板返回 `signalStatus=pending`、`quoteStatus=ready`；
- 4 条公开快照因收盘价大于最高价被质量校验拒绝，同步运行仍记录具体错误，不生成修正行情；
- 东方财富接口在当前网络主动断开，Provider 已切换为同属 AKShare 的新浪公开列表、快照和指数接口，不回退模拟数据。
- Docker 19 使用 `docker-compose.market-data.legacy.yml` 兼容旧 seccomp；默认 Compose 保留系统调用隔离；
- 新浪稳定接口不含行业分类，现阶段保留已有行业并跳过空行业，后续由可替换行业分类 Provider 补齐。
