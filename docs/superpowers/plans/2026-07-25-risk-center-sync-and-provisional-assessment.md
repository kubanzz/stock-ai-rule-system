# 风险中心同步与暂定评估实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为风险中心增加市场/个股手动同步、可解释的暂定风险评估和可用的申万一级行业矩阵。

**架构：** 后端保留现有正式评分和风险闸门，在查询层新增只读暂定评估计算；同步入口以进程内异步任务驱动现有幂等风险工作流。行业历史失败时只把当前申万成分用于当前导航和暂定评估，真实 `availableAt` 继续阻止其进入过去时点正式评分。

**技术栈：** JDK 21、Spring Boot 3.5、MyBatis/MyBatis-Plus、Flyway、MySQL、Vue 3、TypeScript、Ant Design Vue、Vitest、Python 3.12、FastAPI 风险数据网关。

---

## 文件结构

### 后端新增

- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query/ProvisionalRiskAssessmentCalculator.java`：根据快照证据计算暂定分数、等级、维度覆盖和指标摘要。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/sync/RiskSyncJob.java`：同步任务不可变视图。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/sync/RiskSyncJobService.java`：任务互斥、异步执行、状态迁移和最新状态查询。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/controller/RiskSyncController.java`：市场同步、股票同步和任务查询接口。
- `stock-ai-rule-system-service/src/main/resources/db/migration/V3__risk_current_industry_metadata.sql`：为行业父对象保存中文名称。
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/query/ProvisionalRiskAssessmentCalculatorTest.java`
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/sync/RiskSyncJobServiceTest.java`
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/controller/RiskSyncControllerTest.java`

### 后端修改

- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query/dto/RiskAssessmentDto.java`：扩展结论状态、暂定分数、维度和指标状态契约。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query/RiskAssessmentQueryServiceImpl.java`：保留部分维度分数并接入暂定评估。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/engine/RiskLayerComposer.java`：保留未达到正式门槛的分层证据与实际覆盖率。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/model/RiskEvidenceProvenance.java`：记录证据所属层及层权重。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/market/IndustryExposure.java`：携带行业中文名称。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/market/AkToolsMarketRiskSourceClient.java`：解析网关的 `sectorName`。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/workflow/JdbcRiskWorkflowRepository.java`：持久化和读取 `parent_object_name`。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/persistence/entity/RiskObjectExposureEntity.java`：映射行业名称。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/persistence/mapper/RiskObjectExposureMapper.java`：查询当前行业名称。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/persistence/mapper/RiskScoreSnapshotMapper.java`：行业快照返回中文名称，趋势默认限制 120 条。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWorkflowPlanner.java`：新增只采集市场、行业映射和行业行情的计划。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWorkflowPlan.java`：允许没有股票评分目标的市场计划。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/DefaultRiskAfterCloseWorkflow.java`：增加手动同步入口并使用真实当前 `asOf`。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/workflow/RiskWarningWorkflow.java`：元数据采集对象不自动成为评分目标；市场同步可扩展当前行业对象。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWarningConfiguration.java`：注册同步服务依赖。
- `stock-ai-rule-system-service/src/main/resources/application.yml`：默认启用手动风险工作流，定时调度继续关闭。

### 风险网关修改

- `infra/risk-data-gateway/src/risk_gateway/datasets/membership.py`：当前成分返回行业名称、最近交易日有效期和真实可用时间。
- `infra/risk-data-gateway/tests/test_membership.py`：验证当前映射可用于当前暂定评估但不能进入过去时点。
- `docker-compose.market-data.yml`：为 AKTools/网关补充可信 CA 环境配置，不关闭 TLS 校验。

### 前端新增

- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-sync-status.vue`：同步状态条和市场同步按钮。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/shared/risk-indicator-popover.vue`：维度指标明细气泡。

### 前端修改

- `stock-ai-rule-system-ui/apps/web-antd/src/api/stock/risk/types.ts`：新增同步、暂定评估和指标状态类型。
- `stock-ai-rule-system-ui/apps/web-antd/src/api/stock/risk/contract.ts`：新增同步 API 路径。
- `stock-ai-rule-system-ui/apps/web-antd/src/api/stock/risk/index.ts`：新增同步请求与任务轮询函数。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-center-state.ts`：增加 `provisional` 状态及显示选择器。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/shared/risk-score-display.vue`：支持暂定分数标签。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/shared/risk-dimension-bars.vue`：展示实际维度分、覆盖率和指标气泡。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-overview-panel.vue`：市场暂定结论。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-sector-matrix.vue`：行业暂定分数、行业名和数据日期。
- `stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/index.vue`：接入同步状态、个股同步和完成后刷新。
- 对应的 `*.test.ts`：覆盖状态选择、API 契约和关键 UI 文案。

---

### 任务 1：暂定评估计算与查询契约

**文件：**
- 创建：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query/ProvisionalRiskAssessmentCalculator.java`
- 创建：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/query/ProvisionalRiskAssessmentCalculatorTest.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query/dto/RiskAssessmentDto.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query/RiskAssessmentQueryServiceImpl.java`
- 修改：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/query/RiskAssessmentQueryServiceImplTest.java`

- [ ] **步骤 1：写出暂定评估资格和等级上限测试**

测试至少固定以下情境：

```java
@Test
void calculatesWatchProvisionalAssessmentFromTwoDimensions() {
    ProvisionalAssessment result = calculator.calculate(
            new BigDecimal("0.41"),
            evidence("S1", "S", "64.9"),
            evidence("C1", "C", "58.1")
    );

    assertThat(result.conclusionStatus()).isEqualTo("provisional");
    assertThat(result.provisionalScore()).isNotNull();
    assertThat(result.provisionalLevel()).isEqualTo("watch");
}

@Test
void keepsFormalSnapshotFieldsAuthoritativeAtEightyPercent() {
    ProvisionalAssessment result = calculator.formal(
            new BigDecimal("0.80"), new BigDecimal("67"), "warning");

    assertThat(result.conclusionStatus()).isEqualTo("formal");
    assertThat(result.provisionalScore()).isNull();
}

@Test
void returnsInsufficientBelowTwentyPercentOrOneDimension() {
    assertThat(calculator.calculate(new BigDecimal("0.19"),
            evidence("C1", "C", "55")).conclusionStatus()).isEqualTo("insufficient");
}
```

- [ ] **步骤 2：运行测试确认新计算器尚不存在**

运行：

```bash
cd stock-ai-rule-system-service
mvn -q -Dtest=ProvisionalRiskAssessmentCalculatorTest test
```

预期：编译失败，提示 `ProvisionalRiskAssessmentCalculator` 不存在。

- [ ] **步骤 3：实现独立暂定计算器**

计算器使用 `RiskIndicatorCatalog` 的指标权重和现有维度权重：

```java
private static final Map<RiskDimension, BigDecimal> DIMENSION_WEIGHTS = Map.of(
        RiskDimension.STRUCTURAL_FRAGILITY, new BigDecimal("0.30"),
        RiskDimension.SUBSTANTIVE_TRIGGER, new BigDecimal("0.20"),
        RiskDimension.EXTERNAL_TRANSMISSION, new BigDecimal("0.15"),
        RiskDimension.LOCAL_CONFIRMATION, new BigDecimal("0.20"),
        RiskDimension.FORCED_SELLING, new BigDecimal("0.15")
);
```

只有 `available/valid_zero` 且分数非空的证据参与计算。同一指标同一层只选最新 `availableAt`。暂定总分按存在的维度权重归一化；缺少正式门控时将等级上限设为 `watch`。

- [ ] **步骤 4：扩展 DTO**

新增记录：

```java
public record RiskIndicatorStatus(
        String code, String name, String dimension, int weight,
        String status, boolean used, BigDecimal score, BigDecimal rawValue,
        String source, LocalDateTime observedAt, LocalDateTime availableAt,
        String reason
) {}

public record RiskDimensionAssessment(
        String dimension, BigDecimal score, BigDecimal coverage,
        int usedCount, int totalCount, List<RiskIndicatorStatus> indicators
) {}
```

在 `RiskSnapshot` 末尾增加：

```java
String conclusionStatus,
BigDecimal provisionalScore,
String provisionalLevel,
List<RiskDimensionAssessment> dimensions,
LocalDateTime dataAsOf,
int staleTradingDays
```

- [ ] **步骤 5：查询层保留正式字段并附加暂定字段**

`totalScore/level/stage/riskConfidence` 仍只在正式条件满足时返回。V/T/S/C/A 不再因为快照质量为 `insufficient_history` 被统一清空；暂定结果由证据计算器生成。

- [ ] **步骤 6：运行后端查询测试**

```bash
cd stock-ai-rule-system-service
mvn -q -Dtest=ProvisionalRiskAssessmentCalculatorTest,RiskAssessmentQueryServiceImplTest,RiskAssessmentControllerTest test
```

预期：全部通过。

- [ ] **步骤 7：提交**

```bash
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/query \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query/dto/RiskAssessmentDto.java
git commit -m "feat(risk): 增加可解释的暂定风险评估"
```

### 任务 2：保留个股分层的部分证据

**文件：**
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/engine/RiskLayerComposer.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/model/RiskEvidenceProvenance.java`
- 修改：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/engine/RiskLayerComposerTest.java`

- [ ] **步骤 1：增加部分层证据测试**

```java
@Test
void retainsEvidenceAndWeightedCoverageForIncompleteLayers() {
    RiskLayerComposition result = composer.compose(
            incompleteMarket("0.41"), incompleteSector("0.46"), incompleteStock("0.28"));

    assertThat(result.coverage()).isEqualByComparingTo("0.3645");
    assertThat(result.evidence()).isNotEmpty();
    assertThat(result.evidence())
            .allSatisfy(item -> assertThat(item.details()).containsKey("layerWeight"));
    assertThat(result.riskConfidence()).isNull();
}
```

- [ ] **步骤 2：运行测试确认旧实现丢弃所有部分证据**

```bash
cd stock-ai-rule-system-service
mvn -q -Dtest=RiskLayerComposerTest test
```

预期：新测试失败，实际证据为空。

- [ ] **步骤 3：按固定层权重计算实际覆盖**

覆盖率改为：

```java
coverage = market.completeness() * 0.25
         + sector.completeness() * 0.35
         + stock.completeness() * 0.40;
```

所有非空层的可用证据均保留，并写入：

```java
details.put("layerObjectType", layerObject.objectType().getCode());
details.put("layerObjectId", layerObject.objectId());
details.put("layerWeight", layerWeight);
```

正式分层维度分和风险置信度仍只使用满足正式条件的层，不重新分配缺失层权重。

- [ ] **步骤 4：运行引擎和工作流测试**

```bash
cd stock-ai-rule-system-service
mvn -q -Dtest=RiskLayerComposerTest,RiskScoringEngineTest,RiskWarningWorkflowTest test
```

预期：全部通过。

- [ ] **步骤 5：提交**

```bash
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/engine/RiskLayerComposer.java \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/model/RiskEvidenceProvenance.java \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/engine/RiskLayerComposerTest.java
git commit -m "fix(risk): 保留不完整分层的风险证据"
```

### 任务 3：恢复当前申万行业目录和行业快照

**文件：**
- 创建：`stock-ai-rule-system-service/src/main/resources/db/migration/V3__risk_current_industry_metadata.sql`
- 修改：`infra/risk-data-gateway/src/risk_gateway/datasets/membership.py`
- 修改：`infra/risk-data-gateway/tests/test_membership.py`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/market/IndustryExposure.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/market/AkToolsMarketRiskSourceClient.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/workflow/JdbcRiskWorkflowRepository.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/persistence/entity/RiskObjectExposureEntity.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/persistence/mapper/RiskObjectExposureMapper.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/persistence/mapper/RiskScoreSnapshotMapper.java`
- 修改相关 Java 测试。

- [ ] **步骤 1：增加网关当前行业降级测试**

固定断言：

```python
assert row["sectorCode"] == "801120"
assert row["sectorName"] == "食品饮料"
assert row["validFrom"] == query.end_date.isoformat()
assert row["qualityStatus"] == "available"
assert row["availableAt"] == context.fetched_at.isoformat()
assert response.meta.historyComplete is False
```

- [ ] **步骤 2：运行网关测试确认失败**

```bash
docker build --target test -t stock-risk-data-gateway-test infra/risk-data-gateway
docker run --rm --security-opt seccomp=unconfined stock-risk-data-gateway-test \
  pytest -q tests/test_membership.py
```

预期：缺少 `sectorName`，质量状态仍为 `insufficient_history`。

- [ ] **步骤 3：实现当前映射的真实知识时点语义**

历史接口失败且请求包含最近交易日时：

```python
{
    "sectorCode": sector,
    "sectorName": sector_name,
    "validFrom": query.end_date.isoformat(),
    "qualityStatus": "available",
    "observedAt": fetched_at.isoformat(),
    "availableAt": fetched_at.isoformat(),
}
```

批次 `historyComplete` 继续为 `False`。对于结束日期早于当前最近交易日的纯历史请求，继续返回历史不足。

- [ ] **步骤 4：增加 Flyway 字段**

```sql
ALTER TABLE risk_object_exposure
    ADD COLUMN parent_object_name VARCHAR(128) NULL
    AFTER parent_object_id;
```

- [ ] **步骤 5：Java 链路携带和保存行业名称**

`IndustryExposure` 新增 `sectorName`，所有构造点明确传值；历史记录没有名称时允许 `null`。Upsert 同时更新 `parent_object_name`。

- [ ] **步骤 6：行业快照查询返回中文名**

`RiskScoreSnapshotMapper` 对行业对象使用当前有效暴露名称：

```sql
COALESCE(
  NULLIF(sb.name, ''),
  (
    SELECT MAX(e.parent_object_name)
    FROM risk_object_exposure e
    WHERE s.object_type = 'sector'
      AND e.parent_object_id = s.object_id
      AND e.parent_object_name IS NOT NULL
  ),
  s.object_id
) AS object_name
```

- [ ] **步骤 7：运行迁移、Provider 和工作流测试**

```bash
cd stock-ai-rule-system-service
mvn -q -Dtest=RiskMigrationContractTest,AkToolsMarketRiskSourceClientTest,MarketRiskDataProviderTest,JdbcRiskWorkflowRepositoryTest,RiskScoreSnapshotMapperTest test
```

预期：全部通过。

- [ ] **步骤 8：提交**

```bash
git add infra/risk-data-gateway \
  stock-ai-rule-system-service/src/main/resources/db/migration/V3__risk_current_industry_metadata.sql \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk
git commit -m "fix(risk): 恢复当前申万行业映射"
```

### 任务 4：市场与股票异步同步接口

**文件：**
- 创建：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/sync/RiskSyncJob.java`
- 创建：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/sync/RiskSyncJobService.java`
- 创建：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/controller/RiskSyncController.java`
- 创建：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/sync/RiskSyncJobServiceTest.java`
- 创建：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/controller/RiskSyncControllerTest.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWorkflowPlanner.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWorkflowPlan.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/DefaultRiskAfterCloseWorkflow.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/workflow/RiskWarningWorkflow.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWarningConfiguration.java`
- 修改：`stock-ai-rule-system-service/src/main/resources/application.yml`
- 修改相关 runtime/workflow 测试。

- [ ] **步骤 1：增加同步任务状态和互斥测试**

```java
@Test
void reusesActiveMarketJobAndPublishesPartialSuccess() {
    RiskSyncJob first = service.startMarketSync();
    RiskSyncJob second = service.startMarketSync();

    assertThat(second.jobId()).isEqualTo(first.jobId());
    executor.runNext();
    assertThat(service.get(first.jobId()).status()).isEqualTo("partial_success");
}

@Test
void stockSyncNormalizesSymbolAndUsesSeparateScope() {
    RiskSyncJob job = service.startStockSync("600519");
    assertThat(job.scopeKey()).isEqualTo("stock:600519.SH");
}
```

- [ ] **步骤 2：运行测试确认同步服务不存在**

```bash
cd stock-ai-rule-system-service
mvn -q -Dtest=RiskSyncJobServiceTest,RiskSyncControllerTest test
```

预期：编译失败，提示同步类型不存在。

- [ ] **步骤 3：增加市场专用计划**

`RiskWorkflowPlanner.planMarket()` 只生成：

- 全部活动股票的 `sw1_membership` 分块任务；
- `CN-A` 的 market daily、valuation、breadth、cross-market；
- 市场融资和 ETF 数据；
- 三个周期。

元数据任务中的股票不自动成为评分对象。市场行情任务在已经获取当前暴露后扩展出全部当前申万一级行业，并采集 `index_hist_sw`。

- [ ] **步骤 4：增加手动 `asOf` 入口**

```java
public RiskWorkflowRunSummary runManualMarket(LocalDate tradeDate) {
    return run(planner.planMarket(), tradeDate, List.of(), LocalDateTime.now(clock));
}

public RiskWorkflowRunSummary runManualStock(LocalDate tradeDate, String symbol) {
    RiskWorkflowPlan plan = planner.plan(List.of(symbol));
    return run(plan, tradeDate, plan.stockObjects(), LocalDateTime.now(clock));
}
```

正式评分内部仍以交易日收盘截止时点判断证据；当前行业映射可保存并用于当前导航。

- [ ] **步骤 5：实现进程内任务服务**

使用 `ConcurrentHashMap<String, RiskSyncJob>` 保存活动任务，`applicationTaskExecutor` 执行。任务完成时根据 `unavailableDatasets` 判定：

```java
status = summary.unavailableDatasets() == 0 ? "succeeded" : "partial_success";
```

失败只保存清理后的异常摘要。市场和每只股票分别使用稳定 `scopeKey` 互斥。

- [ ] **步骤 6：实现 REST 接口**

```java
@PostMapping("/market")
public AjaxResult syncMarket()

@PostMapping("/stocks/{symbol}")
public AjaxResult syncStock(@PathVariable String symbol)

@GetMapping("/jobs/{jobId}")
public AjaxResult job(@PathVariable String jobId)

@GetMapping("/status")
public AjaxResult status()
```

- [ ] **步骤 7：确保手动工作流默认可用**

`risk.warning.enabled` 默认设为 `true`，`daily-workflow-enabled` 保持 `false`。同步接口仅手动触发，不自动启用调度或正式闸门。

- [ ] **步骤 8：运行同步、runtime 和控制器测试**

```bash
cd stock-ai-rule-system-service
mvn -q -Dtest=RiskSyncJobServiceTest,RiskSyncControllerTest,RiskRuntimeWorkflowTest,RiskWorkflowPlannerTest,RiskWarningConfigurationTest,RiskWarningWorkflowTest test
```

预期：全部通过。

- [ ] **步骤 9：提交**

```bash
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk \
  stock-ai-rule-system-service/src/main/resources/application.yml \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk
git commit -m "feat(risk): 增加市场与个股异步同步"
```

### 任务 5：风险中心暂定结果、同步状态条和指标气泡

**文件：**
- 创建：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-sync-status.vue`
- 创建：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/shared/risk-indicator-popover.vue`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/api/stock/risk/types.ts`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/api/stock/risk/contract.ts`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/api/stock/risk/index.ts`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-center-state.ts`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/shared/risk-score-display.vue`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/shared/risk-dimension-bars.vue`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-overview-panel.vue`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-sector-matrix.vue`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/index.vue`
- 修改相关 `*.test.ts`。

- [ ] **步骤 1：扩展前端契约和状态选择测试**

```ts
expect(riskDataState(provisionalSnapshot)).toBe('provisional');
expect(displayScore(provisionalSnapshot)).toEqual({
  score: 54.7,
  level: 'watch',
  provisional: true,
});
```

同步 API 路径固定为：

```ts
syncMarket: '/risks/sync/market',
syncStock: (symbol: string) => `/risks/sync/stocks/${symbol}`,
syncJob: (jobId: string) => `/risks/sync/jobs/${jobId}`,
syncStatus: '/risks/sync/status',
```

- [ ] **步骤 2：运行前端测试确认契约尚未实现**

```bash
cd stock-ai-rule-system-ui
pnpm --filter=@vben/web-antd exec vitest run \
  apps/web-antd/src/views/stock/risk/center/risk-center-state.test.ts \
  apps/web-antd/src/api/stock/risk/risk-contract.test.ts
```

预期：新断言失败。

- [ ] **步骤 3：实现同步 API 和轮询**

轮询间隔为 1500ms；任务进入终态或组件卸载时停止。重复点击由后端复用任务，前端按钮在活动任务期间显示 loading。

- [ ] **步骤 4：实现状态条**

状态条显示：

- 最新风险数据日期；
- `staleTradingDays`；
- 当前阶段、状态和进度；
- 市场同步按钮；
- 部分成功或失败摘要。

- [ ] **步骤 5：实现暂定分数显示**

统一选择：

```ts
const score = snapshot.conclusionStatus === 'formal'
  ? snapshot.totalScore
  : snapshot.provisionalScore;
const level = snapshot.conclusionStatus === 'formal'
  ? snapshot.level
  : snapshot.provisionalLevel;
```

暂定结果必须显示“暂定评估”标签和完整度。

- [ ] **步骤 6：实现指标气泡**

每个维度右侧按钮显示：

```text
2/6 可用 · 权重 35%
```

点击后按目录顺序展示状态、分数、来源、可用时间和原因。使用 Ant Design Vue `Popover`，不使用仅 hover 触发。

- [ ] **步骤 7：实现行业矩阵和股票同步**

行业格显示中文名、代码、暂定或正式分数、结论状态和数据日期。对象详情为股票时显示“同步该股票”，完成后重新加载总览、行业、股票列表和详情。

- [ ] **步骤 8：运行前端测试和 typecheck**

```bash
cd stock-ai-rule-system-ui
pnpm --filter=@vben/web-antd exec vitest run \
  apps/web-antd/src/views/stock/risk \
  apps/web-antd/src/api/stock/risk
pnpm --filter=@vben/web-antd run typecheck
```

预期：全部通过。

- [ ] **步骤 9：提交**

```bash
git add stock-ai-rule-system-ui/apps/web-antd/src/api/stock/risk \
  stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk
git commit -m "feat(risk-ui): 展示同步状态与暂定风险证据"
```

### 任务 6：集成验证与真实数据同步

**文件：**
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/persistence/mapper/RiskScoreSnapshotMapper.java`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/center/risk-center-state.ts`
- 修改相关趋势和契约测试。

- [ ] **步骤 1：趋势默认限制最近 120 个交易日**

未传 `startDate` 时 SQL 按交易日倒序取 120 条，再在外层按交易日升序返回；显式日期范围继续按范围查询。

- [ ] **步骤 2：运行完整定向测试**

```bash
cd stock-ai-rule-system-service
mvn -q -Dtest='com.jx.tracker.risk.**.*Test' test
```

```bash
cd stock-ai-rule-system-ui
pnpm --filter=@vben/web-antd exec vitest run \
  apps/web-antd/src/views/stock/risk \
  apps/web-antd/src/api/stock/risk
pnpm --filter=@vben/web-antd run typecheck
```

```bash
docker build --target test -t stock-risk-data-gateway-test infra/risk-data-gateway
docker run --rm --security-opt seccomp=unconfined stock-risk-data-gateway-test pytest -q
```

预期：全部通过。

- [ ] **步骤 3：运行项目级验证**

```bash
cd stock-ai-rule-system-service
mvn test
```

```bash
cd stock-ai-rule-system-ui
pnpm check
pnpm build:antd
```

预期：全部通过；如仓库既有非本任务问题失败，保存准确命令、失败文件和错误摘要。

- [ ] **步骤 4：重建并启动真实数据依赖**

```bash
docker-compose -f docker-compose.market-data.yml up -d --build aktools risk-data-gateway
docker-compose -f docker-compose.market-data.yml ps
curl -fsS http://127.0.0.1:18090/health
```

预期：AKTools 和网关均健康。

- [ ] **步骤 5：启动应用并执行市场同步**

调用：

```bash
curl -fsS -X POST http://127.0.0.1:8080/api/risks/sync/market
```

轮询返回的 `jobId` 直到终态，随后查询：

```bash
curl -fsS 'http://127.0.0.1:8080/api/risks/overview?horizon=1-5d'
curl -fsS 'http://127.0.0.1:8080/api/risks/objects?objectType=sector&horizon=1-5d&pageNum=1&pageSize=100'
```

预期：市场返回正式或暂定结论；行业列表非空并包含中文名称。

- [ ] **步骤 6：执行样本股票联动同步**

```bash
curl -fsS -X POST http://127.0.0.1:8080/api/risks/sync/stocks/600519.SH
```

任务结束后验证市场、所属行业和 `600519.SH` 均有最近交易日快照，且指标状态列表非空。

- [ ] **步骤 7：浏览器验收**

打开风险中心，确认：

- 市场显示暂定或正式分数；
- 行业矩阵非空；
- 点击维度显示指标气泡；
- 市场和股票同步按钮状态正确；
- 同步部分失败时旧数据仍可见；
- 趋势表最多默认展示 120 条。

- [ ] **步骤 8：最终提交**

```bash
git add stock-ai-rule-system-service stock-ai-rule-system-ui infra/risk-data-gateway docker-compose.market-data.yml
git commit -m "fix(risk): 完成风险中心数据可用闭环"
```
