# 风险中心可解释展示与关注股票自动同步实现计划

> **执行要求：** 按任务顺序使用测试驱动开发；每个行为先运行失败测试，再写最小实现。完成前使用 verification-before-completion 执行完整验证。

**目标：** 新增关注股票后自动排队生成风险快照并立即出现在风险中心，同时修复总览统计、行业解释、下钻加载和风险证据时间线。

**架构：** 股票池事务提交后发布领域事件，风险模块在 `AFTER_COMMIT` 阶段将批量股票交给统一串行任务队列。盘后风险步骤通过 JDBC 读取真实股票池成员兜底。前端把关注股票与风险快照、同步任务合并为展示行，不伪造风险快照。

**技术栈：** JDK 21、Spring Boot 3.5、MyBatis-Plus/JdbcTemplate、Vue 3、TypeScript、Ant Design Vue、Vitest、pnpm。

---

## 任务 1：股票池发布事务后可消费的新增事件

**新增文件**

- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/domain/event/WatchlistStocksAddedEvent.java`

**修改文件**

- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/service/impl/StockWatchlistServiceImpl.java`
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/service/impl/StockWatchlistServiceImplTest.java`

### 步骤

1. 在 `StockWatchlistServiceImplTest` 增加失败测试：
   - 单只添加成功发布 `poolCode + [symbol]`。
   - 批量添加只发布 `addedSymbols`，跳过和失败股票不发布。
   - 批量没有实际新增时不发布。
2. 运行：

   ```bash
   cd stock-ai-rule-system-service
   mvn -q -Dtest=StockWatchlistServiceImplTest test
   ```

   预期：事件类型或发布行为尚不存在，测试失败。
3. 新增不可变事件：

   ```java
   public record WatchlistStocksAddedEvent(String poolCode, List<String> symbols) {
       public WatchlistStocksAddedEvent {
           symbols = List.copyOf(symbols);
       }
   }
   ```

4. 为服务注入 `ApplicationEventPublisher`，只在数据库插入成功后发布。批量路径在循环结束后发布一次。
5. 重跑定向测试并确认通过。

## 任务 2：风险同步服务支持批量与串行排队

**修改文件**

- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/sync/RiskSyncJob.java`
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/sync/RiskSyncStatus.java`
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/sync/RiskSyncJobService.java`
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWorkflowPlanner.java`
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/DefaultRiskAfterCloseWorkflow.java`
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/sync/RiskSyncJobServiceTest.java`
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/runtime/RiskWorkflowPlannerTest.java`
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/runtime/DefaultRiskAfterCloseWorkflowTest.java`
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/controller/RiskSyncControllerTest.java`

### 步骤

1. 先写失败测试：
   - 市场任务运行时，股票任务状态为 `queued`，不抛 409。
   - 前一任务完成后自动运行下一任务。
   - 前一任务失败后下一任务仍运行。
   - `startWatchlistSync` 对股票标准化、去重并一次调用批量工作流。
   - 相同活动范围返回现有任务。
   - `status` 返回活动任务和有界最近任务。
2. 运行：

   ```bash
   cd stock-ai-rule-system-service
   mvn -q -Dtest=RiskSyncJobServiceTest,RiskWorkflowPlannerTest,DefaultRiskAfterCloseWorkflowTest,RiskSyncControllerTest test
   ```

   预期：排队和批量行为测试失败。
3. `RiskSyncJob` 增加不可变 `symbols` 字段；`RiskSyncStatus` 增加 `recentJobs`。
4. 在 `RiskSyncJobService` 中引入受 `synchronized` 保护的 FIFO 请求队列：
   - `enqueue` 创建或复用任务。
   - `dispatchNext` 只启动一项。
   - `finishAndDispatchNext` 清理活动任务并继续。
   - 任务集合超过上限时只清理已完成的旧任务。
5. 新增 `startWatchlistSync(Collection<String>)`。
6. `RiskWorkflowPlanner.planStockSync(List<String>)` 复用完整计划后过滤手工延后数据集。
7. `DefaultRiskAfterCloseWorkflow.runManualStocks` 一次运行批量计划；单只入口委托它。
8. 重跑定向测试并确认通过。

## 任务 3：事务提交后监听并同步关注股票

**新增文件**

- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/sync/WatchlistRiskSyncListener.java`
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/sync/WatchlistRiskSyncListenerTest.java`

### 步骤

1. 写失败测试：
   - 事件股票交给 `startWatchlistSync`。
   - 空股票事件不启动任务。
   - 监听方法标注 `AFTER_COMMIT`。
2. 运行：

   ```bash
   cd stock-ai-rule-system-service
   mvn -q -Dtest=WatchlistRiskSyncListenerTest test
   ```

3. 新增条件监听器：

   ```java
   @Component
   @ConditionalOnBean(RiskSyncJobService.class)
   final class WatchlistRiskSyncListener {
       @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
       public void onStocksAdded(WatchlistStocksAddedEvent event) { ... }
   }
   ```

4. 重跑测试。

## 任务 4：盘后风险任务默认使用关注股票

**新增文件**

- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskTrackedStockReader.java`
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/JdbcRiskTrackedStockReader.java`
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/runtime/JdbcRiskTrackedStockReaderTest.java`

**修改文件**

- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWarningConfiguration.java`
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/DefaultRiskAfterCloseWorkflow.java`
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/scheduler/RiskWarningStepHandler.java`
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/scheduler/RiskWarningStepHandlerTest.java`
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/runtime/RiskRuntimeWorkflowTest.java`

### 步骤

1. 写失败测试：
   - JDBC 查询只返回真实 A 股股票池中的活跃股票，去重并标准化。
   - 调度请求有显式股票时不查询关注股票。
   - 调度请求为空时使用关注股票。
   - 关注股票为空时 `DefaultRiskAfterCloseWorkflow.runDaily` 使用市场计划，不扩展全市场股票。
2. 运行：

   ```bash
   cd stock-ai-rule-system-service
   mvn -q -Dtest=JdbcRiskTrackedStockReaderTest,RiskWarningStepHandlerTest,RiskRuntimeWorkflowTest test
   ```

3. 实现 JDBC Reader，SQL 连接：
   - `stock_watchlist`
   - `stock_watchlist_item`
   - `stock_base`
4. 在风险配置中注册 Reader。
5. `RiskWarningStepHandler` 按“显式股票 > 关注股票 > 空”选择。
6. `DefaultRiskAfterCloseWorkflow.runDaily` 对空列表使用 `planner.planMarket()`。
7. 重跑测试。

## 任务 5：风险总览拆分正式与暂定统计

**修改文件**

- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query/dto/RiskAssessmentDto.java`
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query/RiskAssessmentQueryServiceImpl.java`
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/query/RiskAssessmentQueryServiceImplTest.java`
- `stock-ai-rule-system-ui/apps/web-antd/src/api/stock/risk/types.ts`
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-center-state.ts`
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-center-state.test.ts`

### 步骤

1. 后端测试固定正式和暂定对象分别计数，暂定对象不能进入 `levelCounts`。
2. 前端测试固定两组计数按等级合并，并保留拆分值。
3. 运行红灯：

   ```bash
   cd stock-ai-rule-system-service
   mvn -q -Dtest=RiskAssessmentQueryServiceImplTest test
   cd ../stock-ai-rule-system-ui
   pnpm --filter @vben/web-antd exec vitest run apps/web-antd/src/views/stock/risk/center/risk-center-state.test.ts
   ```

4. 为 `RiskOverview` 添加 `provisionalLevelCounts`。
5. 查询服务对结论为 provisional 且拥有 `provisionalLevel` 的行计数。
6. 前端增加纯函数 `buildRiskLevelSummaries`。
7. 重跑定向测试。

## 任务 6：合并关注股票、快照与同步状态

**修改文件**

- `stock-ai-rule-system-ui/apps/web-antd/src/api/stock/risk/types.ts`
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-center-state.ts`
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-center-state.test.ts`
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/index.vue`

### 步骤

1. 写失败测试覆盖：
   - 排除 `all`，跨股票池按 symbol 去重。
   - 有快照时优先显示快照。
   - queued/running/failed 映射到等待、同步中和失败。
   - 没有任务和快照时为待同步。
   - 行业选中时，待同步股票只按行业名称匹配。
2. 运行：

   ```bash
   cd stock-ai-rule-system-ui
   pnpm --filter @vben/web-antd exec vitest run apps/web-antd/src/views/stock/risk/center/risk-center-state.test.ts
   ```

3. 增加 `RiskCenterObjectRow` 与 `RiskStockSyncState`，快照允许为空但不构造伪数据。
4. 增加纯函数 `buildTrackedStockRows`。
5. `index.vue` 并行请求 `getWatchlists` 和 `getRiskSyncStatus`，维护原始快照行、关注池和最近任务。
6. 自动任务活动期间轮询同步状态；完成后调用 `loadCenter`。
7. 监听页面 `focus` 事件重新加载关注池和同步状态。
8. 重跑定向测试。

## 任务 7：优化风险中心解释与局部交互

**修改文件**

- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-overview-panel.vue`
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-sector-matrix.vue`
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-object-drilldown.vue`
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-trigger-timeline.vue`
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/index.vue`
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-center-state.ts`
- 对应 `*.test.ts`

### 步骤

1. 写失败测试或静态契约测试固定以下行为：
   - 总览显示主合计及 `正式 X · 暂定 Y`。
   - 行业矩阵解释风险压力分、关注和暂定。
   - `A 股全市场` 与 `CN-A` 同时可见。
   - 下钻组件使用独立 `stockLoading`，行业选中态明确。
   - 待同步股票显示同步按钮且不会请求不存在的详情。
   - 时间线从所有维度筛选可用正分证据并倒序。
2. 运行相关 Vitest，确认红灯。
3. 实现总览统计说明和行业图例。
4. 拆分下钻 `loading` 与 `stockLoading` 属性。
5. 为行业矩阵和下钻传入 `selectedId`。
6. 修改空状态和同步/重试交互。
7. 时间线标题改为“风险证据时间线”，构建函数不再依赖 `activeTriggers`。
8. 重跑相关 Vitest。

## 任务 8：回归与真实链路验收

### 后端验证

```bash
cd stock-ai-rule-system-service
mvn -q -Dtest=StockWatchlistServiceImplTest,RiskSyncJobServiceTest,WatchlistRiskSyncListenerTest,JdbcRiskTrackedStockReaderTest,RiskWarningStepHandlerTest,RiskAssessmentQueryServiceImplTest test
mvn test
```

### 前端验证

```bash
cd stock-ai-rule-system-ui
pnpm --filter @vben/web-antd exec vitest run
pnpm --filter @vben/web-antd typecheck
pnpm lint
pnpm check
pnpm build:antd
```

### 真实冒烟

1. 确认 MySQL、AKTools、风险数据网关、后端和前端健康。
2. 在“我的关注”加入一只当前无风险快照的股票。
3. 检查 `/api/risks/sync/status` 中任务包含该股票且状态从 queued/running 进入终态。
4. 检查 `/api/risks/objects?objectType=stock` 出现该股票快照。
5. 浏览器验证风险中心的关注股票占位、自动刷新、行业选择、总览拆分和风险证据时间线。
6. 检查 Git 状态只包含本次计划内文件，不纳入运行日志。

