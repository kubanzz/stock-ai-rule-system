# 行业风险适用指标与分阶段同步实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 `risk-warning-v2` 行业快照按行业适用指标计算完整度，修复行业观测读取范围，并使行业快照不再被慢速市场宽度同步阻塞。

**架构：** 新增唯一的覆盖配置目录，由评分引擎和查询解释器共同使用；v1 保持全目录语义，v2 行业使用 11 个适用指标。市场手工同步拆为快速行业阶段和慢数据补算阶段，并提供只读已落库数据的两年行业重建任务。

**技术栈：** JDK 21、Spring Boot 3.5.15、MyBatis-Plus/JdbcTemplate、JUnit 5、AssertJ、Mockito、Vue 3、TypeScript、Vitest、pnpm。

---

## 文件结构

- 创建 `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/engine/RiskCoverageProfile.java`：封装单个对象类型的适用指标、总权重和维度权重。
- 创建 `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/engine/RiskCoverageProfileCatalog.java`：按模型版本和对象类型选择唯一覆盖配置。
- 创建 `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/engine/RiskCoverageProfileCatalogTest.java`：覆盖目录的独立契约测试。
- 修改 `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/engine/RiskScoringEngine.java`：使用覆盖配置计算完整度、维度门槛和缺失原因。
- 修改 `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/engine/RiskCoverageGateTest.java`：增加 v2 行业评分和 v1 兼容测试。
- 修改 `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query/ProvisionalRiskAssessmentCalculator.java`：按快照对象和模型版本解释适用性。
- 修改 `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query/RiskAssessmentQueryServiceImpl.java`：把对象类型和模型版本传给查询解释器。
- 修改 `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query/dto/RiskAssessmentDto.java`：维度 DTO 增加 `applicable`。
- 修改 `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/query/ProvisionalRiskAssessmentCalculatorTest.java` 与 `RiskAssessmentQueryServiceImplTest.java`：验证 v1/v2 查询语义。
- 修改 `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/workflow/JdbcRiskWorkflowRepository.java`：市场范围读取市场和全部行业观测。
- 修改 `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/workflow/JdbcRiskWorkflowRepositoryTest.java`：验证市场、行业、个股作用域。
- 修改 `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWorkflowPlanner.java`：提供快速市场计划和慢数据计划。
- 修改 `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/DefaultRiskAfterCloseWorkflow.java`：顺序执行两阶段并汇总结果，提供两年行业重建。
- 修改 `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWorkflowPlan.java`：构造两年只评分请求。
- 修改相关 runtime 测试：验证计划拆分、执行顺序和两年窗口。
- 修改 `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/sync/RiskSyncJobService.java` 和控制器：增加显式行业重建任务。
- 修改同步服务和控制器测试：验证互斥、任务范围和只读重建调用。
- 修改 `stock-ai-rule-system-service/src/main/resources/application.yml`：默认模型版本升级到 `risk-warning-v2`。
- 修改前端风险类型、维度条、指标弹层和测试：展示“不适用”。

### 任务 1：建立对象类型覆盖配置

**文件：**
- 创建：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/engine/RiskCoverageProfileCatalogTest.java`
- 创建：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/engine/RiskCoverageProfile.java`
- 创建：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/engine/RiskCoverageProfileCatalog.java`

- [x] **步骤 1：编写失败的覆盖目录测试**

```java
@Test
void usesSectorApplicableIndicatorsOnlyForRiskWarningV2() {
    RiskCoverageProfile profile = RiskCoverageProfileCatalog.resolve(
            RiskObjectType.SECTOR, "risk-warning-v2");

    assertThat(profile.definitions()).extracting(RiskIndicatorDefinition::code)
            .containsExactlyInAnyOrder(
                    "V3", "V4", "S1", "S2", "S4",
                    "C1", "C3", "C4", "C5", "A3", "A5");
    assertThat(profile.totalWeight()).isEqualTo(205);
    assertThat(profile.dimensionWeight(RiskDimension.SUBSTANTIVE_TRIGGER)).isZero();
}

@Test
void keepsLegacySectorAndV2StockOnTheFullCatalog() {
    assertThat(RiskCoverageProfileCatalog.resolve(
            RiskObjectType.SECTOR, "risk-warning-v1").totalWeight()).isEqualTo(500);
    assertThat(RiskCoverageProfileCatalog.resolve(
            RiskObjectType.STOCK, "risk-warning-v2").totalWeight()).isEqualTo(500);
}
```

- [x] **步骤 2：运行测试验证缺少覆盖目录时失败**

运行：

```bash
cd stock-ai-rule-system-service
mvn -Dtest=RiskCoverageProfileCatalogTest test
```

预期：测试编译失败，提示 `RiskCoverageProfile` 或 `RiskCoverageProfileCatalog` 不存在。

- [x] **步骤 3：实现最小覆盖配置**

`RiskCoverageProfile` 提供以下稳定 API：

```java
public record RiskCoverageProfile(List<RiskIndicatorDefinition> definitions) {
    public int totalWeight();
    public List<RiskIndicatorDefinition> forDimension(RiskDimension dimension);
    public int dimensionWeight(RiskDimension dimension);
    public boolean applies(String indicatorCode);
    public boolean applies(RiskDimension dimension);
}
```

`RiskCoverageProfileCatalog` 只在 `risk-warning-v2 + SECTOR` 时选取以下代码，其余上下文使用 `RiskIndicatorCatalog.definitions()`：

```java
private static final Set<String> V2_SECTOR_CODES = Set.of(
        "V3", "V4", "S1", "S2", "S4",
        "C1", "C3", "C4", "C5", "A3", "A5");
```

- [x] **步骤 4：运行测试验证通过**

运行：`mvn -Dtest=RiskCoverageProfileCatalogTest test`

预期：2 个测试通过，0 失败。

- [x] **步骤 5：提交覆盖目录**

```bash
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/engine \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/engine/RiskCoverageProfileCatalogTest.java
git commit -m "feat(risk): 增加对象类型指标覆盖配置"
```

### 任务 2：让评分引擎按覆盖配置计算

**文件：**
- 修改：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/engine/RiskCoverageGateTest.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/engine/RiskScoringEngine.java`

- [x] **步骤 1：编写 v2 行业失败测试**

新增测试辅助方法，根据行业适用目录生成 AVAILABLE 证据，并构造 `RiskScoreRequest`：

```java
@Test
void scoresACompleteV2SectorAgainstItsApplicableCatalog() {
    RiskScoreResult result = engine.score(request(
            new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801010"),
            "risk-warning-v2",
            evidenceFor(RiskCoverageProfileCatalog.resolve(
                    RiskObjectType.SECTOR, "risk-warning-v2"))));

    assertThat(result.snapshot().completeness()).isEqualByComparingTo("1.0000");
    assertThat(result.snapshot().vScore()).isNotNull();
    assertThat(result.snapshot().tScore()).isNull();
    assertThat(result.snapshot().sScore()).isNotNull();
    assertThat(result.snapshot().cScore()).isNotNull();
    assertThat(result.snapshot().aScore()).isNotNull();
    assertThat(result.snapshot().totalScore()).isNotNull();
}

@Test
void keepsLegacySectorCompletenessOnTheFiveHundredWeightDenominator() {
    RiskScoreResult result = engine.score(request(
            new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801010"),
            "risk-warning-v1",
            transmissionEvidence()));

    assertThat(result.snapshot().completeness()).isEqualByComparingTo("0.1400");
}
```

- [x] **步骤 2：运行测试验证正确失败**

运行：`mvn -Dtest=RiskCoverageGateTest test`

预期：v2 行业完整度仍为 `0.4100` 或正式结论为空，新增测试失败。

- [x] **步骤 3：修改评分引擎**

在 `score` 开始处解析配置：

```java
RiskCoverageProfile profile = RiskCoverageProfileCatalog.resolve(
        request.object().objectType(), request.modelVersion());
```

随后：

- 仅保留 `profile.applies(code)` 的有效证据；
- 完整度分母使用 `profile.totalWeight()`；
- 维度覆盖分母使用 `profile.dimensionWeight(dimension)`；
- 维度不适用时分数为 `null`，但不产生缺失原因；
- V/C/A 必需门槛只对适用维度执行；
- T/S 组合只检查适用维度；
- 总分在适用维度权重之间归一化，完整行业证据全部为 70 分时总分仍为 70 分；
- 指标级缺失原因只遍历 `profile.definitions()`。

- [x] **步骤 4：运行引擎测试验证通过且无回归**

运行：

```bash
mvn -Dtest=RiskCoverageProfileCatalogTest,RiskCoverageGateTest,RiskScoringEngineTest,RiskStateMachineTest,RiskLayerComposerTest test
```

预期：所有指定测试通过。

- [x] **步骤 5：提交评分引擎变更**

```bash
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/engine/RiskScoringEngine.java \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/engine/RiskCoverageGateTest.java
git commit -m "feat(risk): 按对象适用指标计算完整度"
```

### 任务 3：统一查询解释与“不适用”API 语义

**文件：**
- 修改：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/query/ProvisionalRiskAssessmentCalculatorTest.java`
- 修改：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/query/RiskAssessmentQueryServiceImplTest.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query/ProvisionalRiskAssessmentCalculator.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query/RiskAssessmentQueryServiceImpl.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query/dto/RiskAssessmentDto.java`

- [x] **步骤 1：编写查询层失败测试**

```java
@Test
void explainsV2SectorCoverageAgainstApplicableIndicators() {
    Assessment result = calculator.calculate(
            "sector", "risk-warning-v2", new BigDecimal("1.00"),
            new BigDecimal("55"), "watch", false, sectorEvidence());

    RiskDimensionAssessment v = dimension(result, "V");
    RiskDimensionAssessment t = dimension(result, "T");
    assertThat(v.applicable()).isTrue();
    assertThat(v.usedCount()).isEqualTo(2);
    assertThat(v.totalCount()).isEqualTo(2);
    assertThat(t.applicable()).isFalse();
    assertThat(t.indicators()).allSatisfy(indicator ->
            assertThat(indicator.status()).isEqualTo("not_applicable"));
}
```

在服务测试中验证 v1 快照仍返回 14%，v2 快照使用 v2 维度适用性。

- [x] **步骤 2：运行测试验证 API 尚不支持对象上下文**

运行：

```bash
mvn -Dtest=ProvisionalRiskAssessmentCalculatorTest,RiskAssessmentQueryServiceImplTest test
```

预期：编译失败或断言失败，因为 `calculate` 没有对象类型/模型版本参数，DTO 没有 `applicable`。

- [x] **步骤 3：实现共享覆盖语义**

把查询计算方法调整为：

```java
public Assessment calculate(
        String objectType,
        String modelVersion,
        BigDecimal completeness,
        BigDecimal formalScore,
        String formalLevel,
        boolean unavailable,
        List<RiskEvidence> evidence)
```

通过 `RiskObjectType.fromCode(objectType)` 和 `RiskCoverageProfileCatalog.resolve` 获取配置。证据完整度分母、维度分母、正式维度门槛都使用该配置。返回完整指标目录，但非适用项设置：

```java
status = "not_applicable";
used = false;
reason = "当前对象类型不适用该指标";
```

`RiskDimensionAssessment` 增加 `boolean applicable`，`totalCount` 只统计适用指标。

- [x] **步骤 4：更新所有调用点并运行测试**

运行：

```bash
mvn -Dtest=ProvisionalRiskAssessmentCalculatorTest,RiskAssessmentQueryServiceImplTest test
```

预期：全部通过。

- [x] **步骤 5：提交查询契约变更**

```bash
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/query \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/query
git commit -m "feat(risk-api): 区分行业指标不适用与缺失"
```

### 任务 4：修复市场同步的行业观测读取范围

**文件：**
- 修改：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/workflow/JdbcRiskWorkflowRepositoryTest.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/workflow/JdbcRiskWorkflowRepository.java`

- [x] **步骤 1：编写市场范围失败测试**

插入同一日期的市场、两个行业和一个个股观测，构造仅包含 `market:CN-A` 任务的请求：

```java
List<RiskObservation> observations = repository.findObservations(marketRequest);

assertThat(observations).extracting(item -> item.object().objectType())
        .contains(RiskObjectType.MARKET, RiskObjectType.SECTOR)
        .doesNotContain(RiskObjectType.STOCK);
```

同时保留一个明确行业请求测试，确保不会读到其他行业。

- [x] **步骤 2：运行测试验证行业观测未被读取**

运行：`mvn -Dtest=JdbcRiskWorkflowRepositoryTest test`

预期：市场范围结果缺少 `RiskObjectType.SECTOR`，断言失败。

- [x] **步骤 3：最小修改 SQL 对象作用域**

在 `objectSqlScopes` 中识别：

```java
boolean containsMarket = requested.stream().anyMatch(object ->
        object.objectType() == RiskObjectType.MARKET
                && "CN-A".equals(object.objectId()));
boolean includeLayerCandidates = containsStock || containsMarket;
```

首个 SQL scope 在 `includeLayerCandidates` 时加入 `market:CN-A` 与全部 `sector` 谓词；明确行业请求不扩大。

- [x] **步骤 4：运行仓储与工作流测试**

运行：

```bash
mvn -Dtest=JdbcRiskWorkflowRepositoryTest,RiskWarningWorkflowTest test
```

预期：全部通过。

- [x] **步骤 5：提交读取范围修复**

```bash
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/workflow/JdbcRiskWorkflowRepository.java \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/workflow/JdbcRiskWorkflowRepositoryTest.java
git commit -m "fix(risk): 市场评分加载行业观测"
```

### 任务 5：拆分市场手工同步的快速与慢速阶段

**文件：**
- 修改：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/runtime/RiskWorkflowPlannerTest.java`
- 修改：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/runtime/RiskRuntimeWorkflowTest.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWorkflowPlanner.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/DefaultRiskAfterCloseWorkflow.java`

- [x] **步骤 1：编写计划拆分与执行顺序失败测试**

```java
@Test
void separatesImmediateSectorDatasetsFromDeferredMarketDatasets() {
    RiskWorkflowPlan immediate = planner().planMarketImmediate();
    RiskWorkflowPlan deferred = planner().planMarketDeferred();

    assertThat(datasetCodes(immediate)).containsExactly(
            "sw1-membership", "market-daily", "cross-market");
    assertThat(datasetCodes(deferred)).containsExactly(
            "valuation", "breadth", "margin-financing", "etf-fund-flow");
}
```

在 runtime 测试中捕获两次 `workflow.run` 请求，断言快速请求先于包含 `breadth` 的请求，并断言返回汇总为两次结果逐字段求和。

- [x] **步骤 2：运行测试验证方法不存在**

运行：`mvn -Dtest=RiskWorkflowPlannerTest,RiskRuntimeWorkflowTest test`

预期：编译失败，提示新的计划方法不存在。

- [x] **步骤 3：实现两阶段计划和汇总**

新增：

```java
public RiskWorkflowPlan planMarketImmediate();
public RiskWorkflowPlan planMarketDeferred();
```

`DefaultRiskAfterCloseWorkflow.runManualMarket` 在同一 `asOf` 下先后调用两次 `workflow.run`。新增私有 `add` 方法，使用 `Math.addExact` 汇总 `RiskWorkflowRunSummary` 七个计数字段。第二阶段异常继续向上抛出，使同步任务记录失败，但第一阶段已经提交的快照不回滚。

- [x] **步骤 4：运行 runtime 与同步测试**

运行：

```bash
mvn -Dtest=RiskWorkflowPlannerTest,RiskRuntimeWorkflowTest,RiskSyncJobServiceTest,RiskWarningWorkflowTest test
```

预期：全部通过。

- [x] **步骤 5：提交分阶段同步**

```bash
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/runtime
git commit -m "perf(risk): 行业评分先于慢速市场数据发布"
```

### 任务 6：增加最近两年行业 v2 重建任务

**文件：**
- 修改：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/runtime/RiskRuntimeWorkflowTest.java`
- 修改：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/sync/RiskSyncJobServiceTest.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWorkflowPlan.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/DefaultRiskAfterCloseWorkflow.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/sync/RiskSyncJobService.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/controller/RiskSyncController.java`

- [x] **步骤 1：编写两年窗口和同步任务失败测试**

```java
@Test
void buildsAStoredDataOnlyTwoYearSectorRebuildRequest() {
    workflow.rebuildRecentSectorScores(LocalDate.of(2026, 8, 21));

    verify(riskWarningWorkflow).scoreStoredData(argThat(request ->
            request.scoreStartDate().equals(LocalDate.of(2024, 8, 21))
                    && request.endDate().equals(LocalDate.of(2026, 8, 21))));
    verify(riskWarningWorkflow, never()).run(any());
}
```

同步服务测试调用 `startSectorRebuild()`，断言 scope 为 `sector:SW1:rebuild`，并验证已有活动任务时返回 409。控制器使用现有 job DTO 返回异步任务。

- [x] **步骤 2：运行测试验证入口不存在**

运行：`mvn -Dtest=RiskRuntimeWorkflowTest,RiskSyncJobServiceTest,RiskSyncControllerTest test`

预期：编译失败，提示重建方法不存在。

- [x] **步骤 3：实现只读重建请求和任务类型**

`RiskWorkflowPlan` 增加：

```java
public RiskWorkflowRequest recentSectorRebuildRequest(
        LocalDate endDate,
        LocalDateTime asOf,
        String modelVersion,
        LocalTime afterCloseCutoff)
```

其中 `scoreStartDate=endDate.minusYears(2)`，collection start 使用 `baselineCollectionStart(scoreStartDate)`。`DefaultRiskAfterCloseWorkflow.rebuildRecentSectorScores` 必须只调用 `scoreStoredData`。

`RiskSyncJobService` 用内部任务类型区分市场同步、个股同步和行业重建，行业重建复用 `RiskTradeDateResolver`、任务执行器和单活动任务互斥。控制器新增：

```java
@PostMapping("/sectors/rebuild")
public AjaxResult rebuildSectors()
```

- [x] **步骤 4：运行重建与同步测试**

运行：

```bash
mvn -Dtest=RiskRuntimeWorkflowTest,RiskSyncJobServiceTest,RiskSyncControllerTest test
```

预期：全部通过。

- [x] **步骤 5：提交重建入口**

```bash
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/{runtime,sync,controller} \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/{runtime,sync,controller}
git commit -m "feat(risk): 增加两年行业评分重建任务"
```

### 任务 7：前端展示不适用并升级默认模型

**文件：**
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/api/stock/risk/types.ts`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/shared/risk-dimension-bars.vue`
- 修改：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/shared/risk-indicator-popover.vue`
- 创建：`stock-ai-rule-system-ui/apps/web-antd/src/views/stock/risk/shared/risk-applicability-ui.test.ts`
- 修改：`stock-ai-rule-system-service/src/main/resources/application.yml`

- [x] **步骤 1：编写前端失败测试**

增加带 `applicable: false` 的 T 维度 fixture，并验证渲染结果：

```ts
expect(wrapper.text()).toContain('不适用');
expect(wrapper.text()).not.toContain('0/4 可用');
```

增加 `not_applicable` 指标 fixture，打开弹层后验证“不适用”和“当前对象类型不适用该指标”。

- [x] **步骤 2：运行前端测试验证类型或展示失败**

运行：

```bash
cd stock-ai-rule-system-ui
pnpm test:unit apps/web-antd/src/views/stock/risk/shared/risk-applicability-ui.test.ts
```

预期：类型检查或“不适用”断言失败。

- [x] **步骤 3：实现前端适用性展示**

- `RiskIndicatorAvailability` 增加 `not_applicable`；
- `RiskDimensionAssessment` 增加 `applicable: boolean`；
- 维度条在 `!assessment.applicable` 时显示“不适用”；
- 弹层增加 `not_applicable` 灰色标签；
- 弹层按钮在维度不适用时显示“不适用”，否则保持“x/y 可用 · z%”。

同时把配置默认值调整为：

```yaml
model-version: ${RISK_WARNING_MODEL_VERSION:risk-warning-v2}
```

- [x] **步骤 4：运行前端测试与后端配置测试**

运行：

```bash
cd stock-ai-rule-system-ui
pnpm test:unit apps/web-antd/src/views/stock/risk/shared/risk-applicability-ui.test.ts
pnpm --filter @vben/web-antd typecheck
cd ../stock-ai-rule-system-service
mvn -Dtest=RiskWarningConfigurationTest test
```

预期：全部通过。

- [x] **步骤 5：提交前端和版本配置**

```bash
git add stock-ai-rule-system-ui/apps/web-antd/src \
  stock-ai-rule-system-service/src/main/resources/application.yml
git commit -m "feat(risk-ui): 展示行业不适用指标"
```

### 任务 8：完整验证与交付检查

**文件：**
- 检查：本计划列出的全部文件
- 更新：`docs/superpowers/plans/2026-08-23-sector-risk-coverage-profile.md` 的复选框

- [x] **步骤 1：运行后端完整测试**

```bash
cd stock-ai-rule-system-service
mvn test
```

预期：BUILD SUCCESS，0 failures，0 errors。

- [x] **步骤 2：运行前端风险中心测试和静态检查**

```bash
cd stock-ai-rule-system-ui
pnpm test:unit apps/web-antd/src/views/stock/risk
pnpm --filter @vben/web-antd typecheck
pnpm lint
```

预期：所有命令退出码为 0。

- [x] **步骤 3：检查版本、差异和敏感内容**

```bash
git diff --check
git status --short
git log --oneline --decorate -8
git diff dev...HEAD --stat
```

确认：

- 只包含规格范围内文件；
- 没有 Token、密码或真实账号；
- v1 快照兼容测试存在；
- 两年重建没有外部 Provider 调用；
- 风险提示文案未被删除。

- [x] **步骤 4：执行代码审查并修复必须项**

逐项核对设计验收标准、测试证据和最终 diff；发现问题后先补失败测试，再修复实现并重跑相关验证。

- [x] **步骤 5：提交计划进度更新**

```bash
git add docs/superpowers/plans/2026-08-23-sector-risk-coverage-profile.md
git commit -m "docs(risk): 记录行业评分优化实施结果"
```
