# 信号看板前后端完整闭环实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 实现可持久化股票池、真实服务端筛选和真实市场环境数据，并将信号看板重构为已确认的工作台式页面。

**架构：** 新增股票池实体、Mapper 和独立业务服务；将信号看板查询从庞大的 `StockConsoleQueryServiceImpl` 委托给专用聚合服务。前端将页面拆成指标、工具栏、信号表格、市场环境和股票池抽屉组件，页面只协调查询状态与事件。

**技术栈：** JDK 21、Spring Boot 3.5.15、MyBatis-Plus、MySQL/H2、JUnit 5、Mockito、Vue 3、TypeScript、Ant Design Vue、Vben Admin、ECharts、Vitest、pnpm 11。

---

## 文件结构

### 后端新增

- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/domain/entity/StockWatchlist.java`：股票池实体。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/domain/entity/StockWatchlistItem.java`：股票池成员实体。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/mapper/StockWatchlistMapper.java`：股票池 Mapper。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/mapper/StockWatchlistItemMapper.java`：股票池成员 Mapper。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/StockWatchlistService.java`：股票池业务接口。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/impl/StockWatchlistServiceImpl.java`：股票池 CRUD、成员增删和校验。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/StockDashboardQueryService.java`：看板查询接口。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/impl/StockDashboardQueryServiceImpl.java`：筛选、分页、指标、行情与市场环境聚合。
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/service/impl/StockWatchlistServiceImplTest.java`：股票池业务测试。
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/service/impl/StockDashboardQueryServiceImplTest.java`：看板查询测试。

### 后端修改

- `stock-ai-rule-system-service/src/main/resources/db/stock_ai_rule_schema.sql`：新增两张股票池表。
- `stock-ai-rule-system-service/src/main/resources/application.yml`：新增市场基准代码映射。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/domain/vo/StockConsoleVo.java`：新增查询、分页、行业、情绪、风险和股票池写入 VO。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/StockConsoleQueryService.java`：更新看板和股票池方法签名。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/impl/StockConsoleQueryServiceImpl.java`：委托新服务，移除固定市场环境和伪股票池实现。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/controller/StockConsoleController.java`：扩展查询参数和股票池 REST API。
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/controller/StockConsoleControllerTest.java`：更新 Controller 契约测试。

### 前端新增

- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/components/signal-metric-grid.vue`：指标卡。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/components/signal-dashboard-toolbar.vue`：股票池、市场、日期和动作工具栏。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/components/signal-table.vue`：服务端筛选、排序和分页表格。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/components/market-context-panel.vue`：大盘折线、行业强弱、信号情绪和风险概览。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/components/watchlist-manager-drawer.vue`：股票池管理。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/components/stock-picker-drawer.vue`：搜索、勾选和添加股票。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/dashboard-state.ts`：查询默认值、参数归一化和页码重置纯函数。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/dashboard-state.test.ts`：状态纯函数测试。

### 前端修改

- `stock-ai-rule-system-ui/apps/web-antd/src/api/stock/types.ts`：补齐分页、市场环境和股票池类型。
- `stock-ai-rule-system-ui/apps/web-antd/src/api/stock/index.ts`：补齐 CRUD 和查询参数。
- `stock-ai-rule-system-ui/apps/web-antd/src/api/stock/mock.ts`：同步新契约，仅在显式 mock 模式使用。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/index.vue`：改为工作台编排页面。
- `stock-ai-rule-system-ui/apps/web-antd/src/stock-real-backend-contract.test.ts`：扩展前后端契约与风险文案断言。

## 任务 1：股票池数据模型与映射

**文件：** 数据库 schema、两个实体、两个 Mapper。

- [ ] **步骤 1：编写实体映射失败测试**

在 `StockWatchlistServiceImplTest` 中引用 `StockWatchlist` 和 `StockWatchlistItem`，断言表名和唯一业务字段：

```java
assertEquals("my-follow", StockWatchlist.builder().poolCode("my-follow").build().getPoolCode());
assertEquals("600519.SH", StockWatchlistItem.builder().symbol("600519.SH").build().getSymbol());
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd stock-ai-rule-system-service && mvn -Dtest=StockWatchlistServiceImplTest test`

预期：编译失败，提示 `StockWatchlist`、`StockWatchlistItem` 不存在。

- [ ] **步骤 3：添加 schema、实体和 Mapper**

`stock_watchlist` 使用 `pool_code` 唯一键；`stock_watchlist_item` 使用 `watchlist_id, symbol` 唯一键和 `ON DELETE CASCADE`。实体使用 `@TableName`、`@TableId(type = IdType.AUTO)` 和项目现有 `createdTime/updatedTime` 映射风格；Mapper 继承 `BaseMapper<T>`。

- [ ] **步骤 4：运行测试验证通过**

运行：`cd stock-ai-rule-system-service && mvn -Dtest=StockWatchlistServiceImplTest test`

预期：测试通过，0 failures。

- [ ] **步骤 5：提交**

```bash
git add stock-ai-rule-system-service/src/main/resources/db/stock_ai_rule_schema.sql \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/domain/entity/StockWatchlist.java \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/domain/entity/StockWatchlistItem.java \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/mapper/StockWatchlistMapper.java \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/mapper/StockWatchlistItemMapper.java \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/service/impl/StockWatchlistServiceImplTest.java
git commit -m "feat: 新增股票池持久化模型"
```

## 任务 2：股票池业务闭环

**文件：** `StockWatchlistService`、`StockWatchlistServiceImpl`、`StockConsoleVo`、股票池服务测试。

- [ ] **步骤 1：补充失败测试**

使用 Mockito 验证以下行为：首次查询自动确保 `my-follow` 系统池存在；创建自定义池生成稳定 `poolCode`；系统池不可删除；重复成员抛出 `IllegalArgumentException`；添加成员前校验股票存在且市场一致；移除成员调用 `delete`。

```java
assertThrows(IllegalStateException.class, () -> service.deletePool("my-follow"));
assertThrows(IllegalArgumentException.class,
    () -> service.addStock("my-growth", new WatchlistStockMutationRequest("UNKNOWN", null)));
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd stock-ai-rule-system-service && mvn -Dtest=StockWatchlistServiceImplTest test`

预期：编译失败，缺少服务接口、实现和写入 VO。

- [ ] **步骤 3：实现最小业务服务**

接口包含：

```java
List<StockConsoleVo.WatchlistPool> list(String market);
StockConsoleVo.WatchlistPool create(StockConsoleVo.WatchlistMutationRequest request);
StockConsoleVo.WatchlistPool update(String poolCode, StockConsoleVo.WatchlistMutationRequest request);
void delete(String poolCode);
StockConsoleVo.WatchlistPool addStock(String poolCode, StockConsoleVo.WatchlistStockMutationRequest request);
StockConsoleVo.WatchlistPool removeStock(String poolCode, String symbol);
```

使用 `@Transactional` 包裹写操作。`all` 作为虚拟全量池，不写数据库；系统池 `my-follow` 可增删成员但不可删除。

- [ ] **步骤 4：运行测试验证通过**

运行：`cd stock-ai-rule-system-service && mvn -Dtest=StockWatchlistServiceImplTest test`

预期：全部股票池业务测试通过。

- [ ] **步骤 5：提交**

```bash
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/StockWatchlistService.java \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/impl/StockWatchlistServiceImpl.java \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/domain/vo/StockConsoleVo.java \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/service/impl/StockWatchlistServiceImplTest.java
git commit -m "feat: 实现股票池管理闭环"
```

## 任务 3：真实看板筛选、分页与指标

**文件：** `StockDashboardQueryService`、实现、VO、现有控制台服务、查询测试。

- [ ] **步骤 1：编写筛选口径失败测试**

构造跨市场、跨行业和多信号样本，验证：`poolCode` 限制候选代码；市场、日期、股票、信号、行业和置信度均生效；分页后 `signals.size()` 小于 `total`；指标基于分页前结果；价格和涨跌幅来自选定交易日行情；5 日命中率只统计 `hit5d` 非空样本。

```java
var query = new SignalDashboardQuery(LocalDate.of(2026, 7, 10), "A股", "my-follow",
    null, "bullish", "食品饮料", new BigDecimal("0.60"), null, 1, 20, "confidence", "desc");
var result = service.dashboard(query);
assertTrue(result.signals().stream().allMatch(row -> "bullish".equals(row.signal())));
assertEquals(1, result.pageNum());
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd stock-ai-rule-system-service && mvn -Dtest=StockDashboardQueryServiceImplTest test`

预期：编译失败，缺少查询 VO 和聚合服务。

- [ ] **步骤 3：实现查询服务**

新增 `SignalDashboardQuery` record，并在服务中按顺序完成候选股票、信号查询、基础信息/行情关联、内存聚合和稳定分页。允许的排序字段使用白名单映射，拒绝直接拼接用户输入。`StockConsoleQueryServiceImpl.dashboard(...)` 改为委托新服务。

- [ ] **步骤 4：运行测试验证通过**

运行：`cd stock-ai-rule-system-service && mvn -Dtest=StockDashboardQueryServiceImplTest test`

预期：筛选、分页、指标和价格测试全部通过。

- [ ] **步骤 5：提交**

```bash
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/StockDashboardQueryService.java \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/impl/StockDashboardQueryServiceImpl.java \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/domain/vo/StockConsoleVo.java \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/StockConsoleQueryService.java \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/impl/StockConsoleQueryServiceImpl.java \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/service/impl/StockDashboardQueryServiceImplTest.java
git commit -m "feat: 实现信号看板真实筛选与分页"
```

## 任务 4：真实市场环境聚合

**文件：** 看板查询服务、`application.yml`、查询测试。

- [ ] **步骤 1：编写市场环境失败测试**

验证四类输出：基准代码映射；最近 20 个交易日收盘点位按日期升序；行业平均涨跌幅前 3 后 2；7 日信号情绪和高风险占比。另写缺失基准行情测试，断言 `available=false` 且 `trend` 为空。

```java
assertFalse(result.marketContext().available());
assertTrue(result.marketContext().trend().isEmpty());
assertNotEquals("新闻情绪", result.marketContext().sentiment().label());
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd stock-ai-rule-system-service && mvn -Dtest=StockDashboardQueryServiceImplTest test`

预期：新增断言失败，当前服务未返回真实市场环境。

- [ ] **步骤 3：实现市场环境**

在 `application.yml` 增加：

```yaml
stock:
  dashboard:
    benchmark-symbols:
      A股: 000300.SH
      港股: HSI.HK
      美股: SPX.US
```

使用 `@ConfigurationProperties` 或 `@Value` 注入映射；所有序列来自 Mapper 查询结果，不提供固定数值。情绪来源命名为“信号情绪（7 日）”。

- [ ] **步骤 4：运行测试验证通过**

运行：`cd stock-ai-rule-system-service && mvn -Dtest=StockDashboardQueryServiceImplTest test`

预期：市场折线、行业排行、情绪、风险和空状态测试通过。

- [ ] **步骤 5：提交**

```bash
git add stock-ai-rule-system-service/src/main/resources/application.yml \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/impl/StockDashboardQueryServiceImpl.java \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/service/impl/StockDashboardQueryServiceImplTest.java
git commit -m "feat: 接入真实市场环境聚合"
```

## 任务 5：REST 契约

**文件：** `StockConsoleController`、控制台服务接口/实现、Controller 测试。

- [ ] **步骤 1：扩展 Controller 失败测试**

使用 `MockMvc` 验证完整看板查询参数传入 service，并覆盖 `POST/PUT/DELETE /api/watchlists`、成员增删、系统池删除错误映射。

```java
mockMvc.perform(get("/api/signals/dashboard")
    .param("market", "A股").param("pageNum", "2").param("pageSize", "20"))
    .andExpect(status().isOk());
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd stock-ai-rule-system-service && mvn -Dtest=StockConsoleControllerTest test`

预期：新增路由为 404 或方法签名不匹配。

- [ ] **步骤 3：实现 Controller**

看板参数组装为 `SignalDashboardQuery`；股票池 CRUD 委托 `StockWatchlistService`；保留统一 `AjaxResult`。请求体使用 `@Valid` 和 `jakarta.validation` 约束，股票代码、池名称和市场不能为空。

- [ ] **步骤 4：运行 Controller 和服务测试**

运行：`cd stock-ai-rule-system-service && mvn -Dtest=StockConsoleControllerTest,StockWatchlistServiceImplTest,StockDashboardQueryServiceImplTest test`

预期：全部通过，0 failures。

- [ ] **步骤 5：提交**

```bash
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/controller/StockConsoleController.java \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/StockConsoleQueryService.java \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/impl/StockConsoleQueryServiceImpl.java \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/controller/StockConsoleControllerTest.java
git commit -m "feat: 扩展信号看板与股票池接口"
```

## 任务 6：前端 API 与状态模型

**文件：** stock API 类型、API 实现、mock、契约测试、`dashboard-state.ts` 及测试。

- [ ] **步骤 1：编写失败测试**

扩展 `stock-real-backend-contract.test.ts`，断言创建、更新、删除股票池 API 路径和分页字段；新增 `dashboard-state.test.ts`：市场/池/日期/筛选变化将 `pageNum` 重置为 1，表格分页只更新页码和页大小。

```ts
expect(resetDashboardPage({ pageNum: 3, pageSize: 20 }, { signal: 'bullish' }))
  .toMatchObject({ pageNum: 1, pageSize: 20, signal: 'bullish' });
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd stock-ai-rule-system-ui && pnpm vitest run apps/web-antd/src/stock-real-backend-contract.test.ts apps/web-antd/src/views/stock/signals/dashboard-state.test.ts`

预期：缺少 API、类型和状态函数导致失败。

- [ ] **步骤 3：实现类型、API 和纯状态函数**

新增 `WatchlistMutationRequest`、`IndustryStrength`、`SignalSentiment`、`RiskOverview`、分页字段和完整 `SignalDashboardQuery`。API 使用现有 `requestOrMock`，真实模式不得静默回退 mock。

- [ ] **步骤 4：运行测试和 typecheck**

运行：`cd stock-ai-rule-system-ui && pnpm vitest run apps/web-antd/src/stock-real-backend-contract.test.ts apps/web-antd/src/views/stock/signals/dashboard-state.test.ts && pnpm --filter @vben/web-antd typecheck`

预期：测试通过且 TypeScript 0 errors。

- [ ] **步骤 5：提交**

```bash
git add stock-ai-rule-system-ui/apps/web-antd/src/api/stock/types.ts \
  stock-ai-rule-system-ui/apps/web-antd/src/api/stock/index.ts \
  stock-ai-rule-system-ui/apps/web-antd/src/api/stock/mock.ts \
  stock-ai-rule-system-ui/apps/web-antd/src/stock-real-backend-contract.test.ts \
  stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/dashboard-state.ts \
  stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals/dashboard-state.test.ts
git commit -m "feat: 完善信号看板前端数据契约"
```

## 任务 7：工作台组件与完整页面

**文件：** 六个 signals 组件、`index.vue`、前端契约测试。

- [ ] **步骤 1：编写页面结构失败测试**

在契约测试中读取页面和组件源码，断言：页面包含六个职责组件；市场环境组件使用 `EchartsUI` 且 `series.type` 为 `line`；存在“信号情绪（7 日）”；风险提示仍绑定后端 `riskDisclaimer`；页面不包含“保证收益”“确定性预测结果”。

- [ ] **步骤 2：运行测试验证失败**

运行：`cd stock-ai-rule-system-ui && pnpm vitest run apps/web-antd/src/stock-real-backend-contract.test.ts`

预期：缺少组件和新页面结构导致失败。

- [ ] **步骤 3：实现指标、工具栏、表格和市场侧栏**

按已确认 V3 布局实现。使用 Ant Design 图标按钮和 tooltip；卡片圆角不超过 8px；表格服务端分页，股票与操作列固定；ECharts 折线使用日期类目和真实 `trend`；`available=false` 时显示空状态。

- [ ] **步骤 4：实现股票池管理和选股抽屉**

股票池管理支持创建、重命名、删除和成员移除；选股抽屉支持按市场搜索、多选、目标池和可选分组。写操作失败时不关闭抽屉，成功后发出 `changed` 事件。

- [ ] **步骤 5：重构 `index.vue` 编排**

页面维护单一 `SignalDashboardQuery`，并行加载看板和股票池；市场变化校正池；筛选变化重置页码；详情跳转携带 `date`；导出按当前筛选生成 CSV；加载错误使用 `message.error`，风险提示始终渲染。

- [ ] **步骤 6：运行测试、typecheck 和构建**

运行：

```bash
cd stock-ai-rule-system-ui
pnpm vitest run apps/web-antd/src
pnpm --filter @vben/web-antd typecheck
pnpm --filter @vben/web-antd build
```

预期：全部命令退出码为 0。

- [ ] **步骤 7：提交**

```bash
git add stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals \
  stock-ai-rule-system-ui/apps/web-antd/src/stock-real-backend-contract.test.ts
git commit -m "feat: 重构股票信号工作台"
```

## 任务 8：全量回归与浏览器验收

**文件：** 仅修复回归发现的问题，不新增范围。

- [ ] **步骤 1：运行后端全量测试**

运行：`cd stock-ai-rule-system-service && mvn test`

预期：BUILD SUCCESS，0 failures，0 errors。

- [ ] **步骤 2：运行前端全量检查**

运行：

```bash
cd stock-ai-rule-system-ui
pnpm vitest run apps/web-antd/src
pnpm --filter @vben/web-antd typecheck
pnpm --filter @vben/web-antd build
```

预期：全部退出码为 0。

- [ ] **步骤 3：启动应用并执行浏览器验收**

后端运行：`cd stock-ai-rule-system-service && mvn spring-boot:run`

前端运行：`cd stock-ai-rule-system-ui && pnpm dev:antd`

使用浏览器验证桌面 `1440x900` 和移动 `390x844`：创建股票池、添加股票、切池筛选、翻页排序、查看大盘折线、进入详情、移除股票、删除自定义池。检查无重叠、空白图表和控制台错误。

- [ ] **步骤 4：检查风险与数据真实性**

运行：

```bash
rg -n "保证收益|确定性预测结果|新闻情绪|3692\.61" \
  stock-ai-rule-system-service/src/main \
  stock-ai-rule-system-ui/apps/web-antd/src/views/stock/signals
```

预期：不存在保证性文案、固定指数值或伪新闻情绪。

- [ ] **步骤 5：提交验收修复**

仅在步骤 1-4 产生修复时执行：

```bash
git add stock-ai-rule-system-service stock-ai-rule-system-ui
git commit -m "fix: 修正信号看板验收问题"
```
