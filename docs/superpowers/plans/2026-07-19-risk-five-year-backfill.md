# 风险预警五年真实回填命令实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 提供一个默认关闭、显式确认、样本先行、覆盖不足即停止的一次性内部命令，并用真实落库数据验证五年风险回填、三个周期快照、断点幂等与影子闸门。

**架构：** 独立非 Web `main` 启动现有 Spring 容器，命令 Runner 只编排预检、确定性抽样、`RiskBackfillService`、只读覆盖评估与原子 JSON 报告。采集和评分继续复用现有 Provider/Workflow；命令不新增写 API，也不在普通应用启动时自动运行。

**技术栈：** JDK 21、Spring Boot 3.5.15、Spring JDBC、MyBatis-Plus、Flyway、Jackson、JUnit 5、AssertJ、Mockito、MySQL 8/Testcontainers、Maven。

---

## 实现约束

- 所有代码在 `codex/risk-five-year-backfill` 分支和 `.worktrees/risk-five-year-backfill` worktree 完成。
- `application.yml` 中风险模块、五年回填和命令入口继续默认关闭。
- 命令必须同时验证 `risk-warning.enabled`、`backfill-enabled`、`backfill-command.enabled` 和确认令牌 `BACKFILL_5Y`。
- `staged` 只有在 50 只样本股的实际覆盖关卡通过后才能扩全市场；`full` 必须读取同模型、同结束日的既有样本通过报告。
- 26 项覆盖只统计真实落库的 `available`/`valid_zero` 观测，并校验复合指标必需组件；不能用 Provider 的静态支持声明代替实际数据。
- 历史行情、估值、宽度、跨市场和历史申万归属需要 `risk-warning.derived-gateway-base-url`。地址缺失或探测失败必须在预检/报告中明确失败，不能降级成假数据。
- `RiskBackfillService` 仍是唯一五年业务入口；CLI 不直接写观测、事件、快照、证据或闸门表。
- 不修改 V1/V2 迁移；现有唯一键和 `risk_ingestion_checkpoint` 继续承担幂等与续跑。

## Task 1：定义命令契约、模式和退出码

**Files:**

- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillMode.java`
- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillExitCode.java`
- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillCommandProperties.java`
- Test: `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/RiskBackfillCommandPropertiesTest.java`

- [ ] **Step 1：先写失败测试**

测试覆盖：默认 `staged/50/target/risk-backfill/reports`、模式大小写绑定、样本数 `1..500`、必填结束日、`symbols` 仅允许 `sample`、确认令牌、八个固定退出码。

```java
@Test
void rejectsExecutionWithoutExactConfirmationToken() {
    RiskBackfillCommandProperties properties = validProperties();
    properties.setConfirmation("backfill_5y");

    assertThatThrownBy(properties::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("BACKFILL_5Y");
}

@Test
void explicitSymbolsAreSampleOnly() {
    RiskBackfillCommandProperties properties = validProperties();
    properties.setMode(RiskBackfillMode.STAGED);
    properties.setSymbols(List.of("600519.SH"));

    assertThatThrownBy(properties::validate)
            .hasMessageContaining("symbols are only allowed in sample mode");
}
```

- [ ] **Step 2：运行测试并确认红灯**

Run:

```bash
cd stock-ai-rule-system-service
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest=RiskBackfillCommandPropertiesTest test
```

Expected: 编译失败，提示三个生产类型不存在。

- [ ] **Step 3：实现最小命令契约**

```java
public enum RiskBackfillMode {
    SAMPLE, STAGED, FULL
}

public enum RiskBackfillExitCode {
    SUCCESS(0), CONFIGURATION_ERROR(2), PREFLIGHT_ERROR(3),
    SAMPLE_EXECUTION_ERROR(4), SAMPLE_GATE_REJECTED(5),
    FULL_EXECUTION_ERROR(6), FINAL_VALIDATION_ERROR(7), REPORT_WRITE_ERROR(8);

    private final int code;
}
```

`RiskBackfillCommandProperties` 使用前缀 `stock-ai-rule.risk-warning.backfill-command`，字段为：

```java
private boolean enabled;
private RiskBackfillMode mode = RiskBackfillMode.STAGED;
private LocalDate endDate;
private int sampleSize = 50;
private List<String> symbols = List.of();
private String confirmation;
private Path reportDirectory = Path.of("target/risk-backfill/reports");
```

`validate()` 返回规范化不可变配置，拒绝空结束日、错误令牌、越界样本数、空报告目录和非 `sample` 的显式代码。

- [ ] **Step 4：运行测试并确认绿灯**

Run: Task 1 的定向 Maven 命令。

Expected: `Tests run: ... Failures: 0, Errors: 0`。

- [ ] **Step 5：提交**

```bash
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill \
        stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/RiskBackfillCommandPropertiesTest.java
git commit -m "feat(risk): 定义五年回填命令契约"
```

## Task 2：实现确定性样本选择

**Files:**

- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillSampleSelector.java`
- Test: `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/RiskBackfillSampleSelectorTest.java`

- [ ] **Step 1：先写失败测试**

覆盖显式代码规范化、未知代码拒绝、重复代码去重、全序列固定步长抽样、股票不足时全取、相同输入重复结果一致。

```java
@Test
void samplesAcrossTheWholeSortedUniverseDeterministically() {
    List<String> universe = IntStream.rangeClosed(1, 100)
            .mapToObj(i -> "%06d.SH".formatted(i)).toList();

    List<String> sample = selector.select(universe, List.of(), 5);

    assertThat(sample).containsExactly(
            "000001.SH", "000026.SH", "000051.SH", "000075.SH", "000100.SH");
    assertThat(selector.select(universe, List.of(), 5)).isEqualTo(sample);
}
```

- [ ] **Step 2：运行测试并确认红灯**

Run:

```bash
cd stock-ai-rule-system-service
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest=RiskBackfillSampleSelectorTest test
```

Expected: `RiskBackfillSampleSelector` 不存在。

- [ ] **Step 3：实现最小选择器**

先用 `AshareRiskObjectCatalog.stock` 标准化并排序活跃股票；显式样本必须是活跃集合子集。自动样本用首尾包含的等距索引：

```java
int index = sampleSize == 1
        ? 0
        : (int) Math.round((double) position * (universe.size() - 1) / (sampleSize - 1));
```

结果去重、不可变，且不会只偏向最小代码。

- [ ] **Step 4：运行测试并提交**

```bash
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest=RiskBackfillSampleSelectorTest test
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillSampleSelector.java \
        stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/RiskBackfillSampleSelectorTest.java
git commit -m "feat(risk): 添加确定性回填样本选择"
```

## Task 3：实现只读安全预检

**Files:**

- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillPreflightRepository.java`
- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/JdbcRiskBackfillPreflightRepository.java`
- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillSourceProbe.java`
- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/HttpRiskBackfillSourceProbe.java`
- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillPreflight.java`
- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillPreflightService.java`
- Test: `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/JdbcRiskBackfillPreflightRepositoryTest.java`
- Test: `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/RiskBackfillPreflightServiceTest.java`

- [ ] **Step 1：先写 JDBC 与服务失败测试**

H2/JdbcTemplate 测试建立最小 `flyway_schema_history`、`trade_calendar`、`risk_gate_result` 表，覆盖：V2 成功、低于 V2 失败、结束日非开市日失败、任意 `enforced=1` 失败。服务测试覆盖四开关、模型版本、分块、空股票池、AKTools/衍生网关探测和报告目录可写性。

```java
@Test
void rejectsMissingHistoricalDerivedGatewayBeforeCallingProviders() {
    when(sourceProbe.probeAkTools()).thenReturn(SourceProbeResult.reachable("aktools"));
    when(sourceProbe.probeDerivedGateway()).thenReturn(
            SourceProbeResult.unreachable("derived gateway is not configured"));

    RiskBackfillPreflight result = service.check(validCommand(), warningProperties(), universe());

    assertThat(result.ready()).isFalse();
    assertThat(result.failures()).contains("derived gateway is not configured");
}
```

- [ ] **Step 2：运行测试并确认红灯**

```bash
cd stock-ai-rule-system-service
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest='JdbcRiskBackfillPreflightRepositoryTest,RiskBackfillPreflightServiceTest' test
```

- [ ] **Step 3：实现只读预检**

仓储查询限定为：

```sql
SELECT version
FROM flyway_schema_history
WHERE success = 1
ORDER BY installed_rank DESC
LIMIT 1;

SELECT COUNT(*)
FROM trade_calendar
WHERE market IN ('CN', 'A股') AND trade_date = ? AND is_open = 1;

SELECT COUNT(*)
FROM risk_gate_result
WHERE enforced <> 0;
```

源探测只验证小响应：AKTools 调用 `/api/public/stock_info_a_code_name` 并要求 HTTP 2xx/JSON 数组；衍生网关调用 `/api/risk/market-daily`，请求一个市场对象和单日，允许空 `data`，但必须返回 `meta.historyComplete` 字段。日志和结果不包含 URL 用户信息、Token 或响应正文。

预检服务只聚合错误，不调用 `RiskBackfillService`。它还通过 `Files.createDirectories` 和临时文件创建/删除验证报告目录，失败归为退出码 3。

- [ ] **Step 4：运行测试并提交**

```bash
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest='JdbcRiskBackfillPreflightRepositoryTest,RiskBackfillPreflightServiceTest' test
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill \
        stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill
git commit -m "feat(risk): 增加五年回填安全预检"
```

## Task 4：基于真实落库数据实现覆盖关卡

**Files:**

- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillReadinessRepository.java`
- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/JdbcRiskBackfillReadinessRepository.java`
- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillReadinessData.java`
- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillIndicatorStatus.java`
- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillReadiness.java`
- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillReadinessEvaluator.java`
- Test: `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/JdbcRiskBackfillReadinessRepositoryTest.java`
- Test: `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/RiskBackfillReadinessEvaluatorTest.java`

- [ ] **Step 1：先写失败测试**

覆盖以下边界：

- 26 项目录全部输出状态，未出现项为缺失；
- 只统计 `available/valid_zero`，拒绝 NULL、`stale/unavailable/insufficient_history` 和 `available_at > asOf`；
- `V1/C2/C5/A4` 等复合指标只有必需 `component_code` 全部存在才可用；
- 权重覆盖恰好 `0.8000` 通过，少一项失败；
- V/C/A 各 `0.6000`，且 T/S 至少一侧 `0.6000`；
- 核心行情日期覆盖评分窗口首尾；
- 市场 `CN-A` 的 `1-5d/5-20d/20-60d` 均有快照；
- 正式快照 `completeness >= 0.80` 且等级非空；
- 任意时间倒置或 `enforced=true` 失败。

```java
@Test
void catalogSupportWithoutPersistedEvidenceDoesNotCountAsCoverage() {
    RiskBackfillReadinessData data = fixtureWithOnly(Set.of("V1", "V3", "C1"));

    RiskBackfillReadiness result = evaluator.evaluate(data);

    assertThat(result.weightedCoverage()).isLessThan(new BigDecimal("0.8000"));
    assertThat(result.ready()).isFalse();
    assertThat(result.failures()).contains("实际指标加权覆盖率低于 80%");
}
```

- [ ] **Step 2：运行测试并确认红灯**

```bash
cd stock-ai-rule-system-service
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest='JdbcRiskBackfillReadinessRepositoryTest,RiskBackfillReadinessEvaluatorTest' test
```

- [ ] **Step 3：实现分块只读仓储**

`load(modelVersion, collectionStart, scoreStart, endDate, asOf, symbols)` 使用 `NamedParameterJdbcTemplate`，每 250 个股票分块，范围包含：`market:CN-A`、样本股票、样本在窗口内有效的申万行业。核心查询包括：

```sql
SELECT indicator_code, component_code, quality_status,
       COUNT(*) AS observation_count,
       MIN(trade_date) AS earliest_date,
       MAX(trade_date) AS latest_date
FROM risk_indicator_observation
WHERE trade_date BETWEEN :scoreStart AND :endDate
  AND available_at <= :asOf
  AND indicator_value IS NOT NULL
  AND quality_status IN ('available', 'valid_zero')
  AND ((object_type = 'market' AND object_id = 'CN-A')
       OR (object_type = 'stock' AND object_id IN (:stockObjectIds))
       OR (object_type = 'sector' AND object_id IN (:sectorObjectIds)))
GROUP BY indicator_code, component_code, quality_status;
```

另查：`market_daily` 核心日期范围、模型快照按周期统计、正式快照统计、各风险表 `available_at < observed_at` 数量、`risk_gate_result.enforced <> 0` 数量、checkpoint 数据集质量与错误。只读结果汇总成 `RiskBackfillReadinessData`。

- [ ] **Step 4：实现评估器**

复用 `RiskIndicatorCatalog` 的权重和维度，复用 `RiskIndicatorComponentCatalog.components(code)` 判断必需组件。分母是 26 项总权重；分子只加入实际可用指标权重：

```java
BigDecimal weightedCoverage = BigDecimal.valueOf(availableWeight)
        .divide(BigDecimal.valueOf(catalogWeight), 4, RoundingMode.HALF_UP);
```

输出 `ready`、每项状态、五维覆盖、日期、周期、正式快照、时间约束、影子约束以及稳定排序的失败原因。

- [ ] **Step 5：运行测试并提交**

```bash
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest='JdbcRiskBackfillReadinessRepositoryTest,RiskBackfillReadinessEvaluatorTest' test
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill \
        stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill
git commit -m "feat(risk): 实现真实数据覆盖关卡"
```

## Task 5：实现可审计 JSON 报告与 full 前置报告校验

**Files:**

- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillReport.java`
- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillReportStore.java`
- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/JacksonRiskBackfillReportStore.java`
- Test: `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/JacksonRiskBackfillReportStoreTest.java`

- [ ] **Step 1：先写失败测试**

测试临时目录：文件名含 UTC/上海时区开始时间、模式和结束日；先写 `.tmp` 再原子移动；JSON 可重新读取；异常报告包含阶段和根因摘要；不包含数据库密码、Token、外部响应正文；`full` 只接受同模型、同结束日、`sampleGatePassed=true` 的完成报告。

```java
@Test
void fullModeRequiresMatchingPassedSampleReport() {
    store.write(report("risk-v1", LocalDate.parse("2026-07-10"), true));

    assertThat(store.hasPassedSampleGate(
            directory, "risk-v1", LocalDate.parse("2026-07-10"))).isTrue();
    assertThat(store.hasPassedSampleGate(
            directory, "risk-v2", LocalDate.parse("2026-07-10"))).isFalse();
}
```

- [ ] **Step 2：运行测试并确认红灯**

```bash
cd stock-ai-rule-system-service
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest=JacksonRiskBackfillReportStoreTest test
```

- [ ] **Step 3：实现报告与原子存储**

`RiskBackfillReport` 至少包含：运行 ID、模式、模型、阶段、开始/结束时间、退出码、三个日期窗口、样本代码、股票/分块数、样本/全量 `RiskWorkflowRunSummary`、覆盖结果、checkpoint 状态和失败原因。

```java
Path temporary = Files.createTempFile(directory, fileName, ".tmp");
objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), report);
try {
    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
} catch (AtomicMoveNotSupportedException ignored) {
    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
}
```

读取历史报告时只扫描命令生成的 `risk-backfill-*.json`，解析失败跳过并记录安全摘要。

- [ ] **Step 4：运行测试并提交**

```bash
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest=JacksonRiskBackfillReportStoreTest test
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill \
        stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/JacksonRiskBackfillReportStoreTest.java
git commit -m "feat(risk): 添加五年回填审计报告"
```

## Task 6：实现 sample/staged/full 编排状态机

**Files:**

- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillCommandResult.java`
- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillCommandRunner.java`
- Test: `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/RiskBackfillCommandRunnerTest.java`

- [ ] **Step 1：先写失败测试**

覆盖：

- 配置错误不预检、不调用 Provider，退出 2；
- 预检错误不调用回填，退出 3；
- `sample` 只执行样本，门控通过退出 0；
- 样本执行异常退出 4；
- 样本 `unavailableDatasetCount > 0` 或覆盖失败退出 5，且全市场零调用；
- `staged` 样本通过后才用空 symbols 执行全量；
- `full` 无匹配样本报告退出 2；
- 全量异常/不可用退出 6；
- 最终验收失败退出 7；
- 所有路径均尝试写报告，报告失败覆盖为退出 8。

```java
@Test
void stagedNeverCallsFullBackfillWhenSampleGateFails() {
    when(backfillService.runFiveYearBackfill(endDate, sampleSymbols))
            .thenReturn(Optional.of(summaryWithUnavailableDataset()));

    RiskBackfillCommandResult result = runner.run();

    assertThat(result.exitCode()).isEqualTo(RiskBackfillExitCode.SAMPLE_GATE_REJECTED);
    verify(backfillService, never()).runFiveYearBackfill(endDate, List.of());
}
```

- [ ] **Step 2：运行测试并确认红灯**

```bash
cd stock-ai-rule-system-service
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest=RiskBackfillCommandRunnerTest test
```

- [ ] **Step 3：实现 Runner**

Runner 依赖命令/风险配置、`RiskUniverseReader`、选择器、预检服务、`RiskBackfillService`、readiness 仓储/评估器、报告存储和 `Clock`。状态机固定为：

```java
validateConfiguration();
preflight();
if (mode == FULL) {
    requireMatchingPassedSampleReport();
    return runFullAndValidate();
}
runSample();
evaluateSample();
if (!sampleReady || mode == SAMPLE) {
    return finishSample();
}
return runFullAndValidate();
```

捕获异常时保留异常类型和最内层 message，但不序列化堆栈、连接串或完整响应。`RiskBackfillService` 返回空 Optional 视为配置错误，不视为成功。

- [ ] **Step 4：运行测试并提交**

```bash
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest=RiskBackfillCommandRunnerTest test
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill \
        stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/RiskBackfillCommandRunnerTest.java
git commit -m "feat(risk): 编排分阶段五年回填"
```

## Task 7：接入独立非 Web main，确保普通启动零副作用

**Files:**

- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillCommandConfiguration.java`
- Create: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillCommandApplication.java`
- Modify: `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWarningConfiguration.java`
- Modify: `stock-ai-rule-system-service/src/main/resources/application.yml`
- Test: `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/RiskBackfillCommandConfigurationTest.java`
- Test: `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/RiskBackfillCommandApplicationTest.java`
- Modify test: `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/runtime/RiskWarningConfigurationTest.java`

- [ ] **Step 1：先写失败测试**

`ApplicationContextRunner` 验证默认只有配置属性、没有 Runner；三个开关开启时才装配完整命令 bean 图；普通 `TrackerApplication` 上下文即使风险运行时开启，也不会自动调用 Runner。main 测试调用包级 `run(String...)` 并验证返回 Runner 退出码且 Context 已关闭，禁止在单测中直接 `System.exit`。

- [ ] **Step 2：运行测试并确认红灯**

```bash
cd stock-ai-rule-system-service
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest='RiskBackfillCommandConfigurationTest,RiskBackfillCommandApplicationTest,RiskWarningConfigurationTest' test
```

- [ ] **Step 3：实现条件装配与独立 main**

在 `RiskWarningConfiguration` 的 `@EnableConfigurationProperties` 注册命令属性；`RiskBackfillCommandConfiguration` 使用：

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "stock-ai-rule.risk-warning.backfill-command",
        name = "enabled", havingValue = "true")
class RiskBackfillCommandConfiguration { }
```

它只创建普通 Runner bean，不实现 `ApplicationRunner/CommandLineRunner`。独立入口显式取 bean 并运行：

```java
static int run(String... args) {
    SpringApplication application = new SpringApplication(TrackerApplication.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    try (ConfigurableApplicationContext context = application.run(args)) {
        RiskBackfillCommandRunner runner = context.getBean(RiskBackfillCommandRunner.class);
        return runner.run().exitCode().code();
    }
}

public static void main(String[] args) {
    System.exit(run(args));
}
```

`application.yml` 新增环境变量映射，仍全部安全默认：

```yaml
backfill-command:
  enabled: ${RISK_WARNING_BACKFILL_COMMAND_ENABLED:false}
  mode: ${RISK_WARNING_BACKFILL_MODE:staged}
  end-date: ${RISK_WARNING_BACKFILL_END_DATE:}
  sample-size: ${RISK_WARNING_BACKFILL_SAMPLE_SIZE:50}
  symbols: ${RISK_WARNING_BACKFILL_SYMBOLS:}
  confirmation: ${RISK_WARNING_BACKFILL_CONFIRMATION:}
  report-directory: ${RISK_WARNING_BACKFILL_REPORT_DIRECTORY:target/risk-backfill/reports}
```

- [ ] **Step 4：运行测试并提交**

```bash
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest='RiskBackfillCommandConfigurationTest,RiskBackfillCommandApplicationTest,RiskWarningConfigurationTest' test
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill \
        stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWarningConfiguration.java \
        stock-ai-rule-system-service/src/main/resources/application.yml \
        stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk
git commit -m "feat(risk): 接入独立五年回填命令"
```

## Task 8：补齐断点幂等与 MySQL 契约验证

**Files:**

- Modify: `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/workflow/RiskWarningWorkflowTest.java`
- Create: `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/RiskBackfillMySqlIntegrationTest.java`

- [ ] **Step 1：写断点续跑失败测试**

固定 Provider 第一次返回部分成功断点、第二次收到原 cursor 后返回后续页。断言成功 cursor 会传回 Provider；`unavailable/insufficient_history` 不推进 cursor；第二次 upsert 后观测、快照、证据、闸门行数不重复。

```java
@Test
void retryPassesPersistedCursorAndKeepsSnapshotsIdempotent() {
    workflow.run(request);
    workflow.run(request);

    assertThat(provider.requests().get(1).checkpoint().cursor()).isEqualTo("page-1");
    assertThat(repository.snapshotIdentityCount()).isEqualTo(
            repository.distinctSnapshotIdentityCount());
}
```

- [ ] **Step 2：运行 workflow 定向测试并确认结果**

```bash
cd stock-ai-rule-system-service
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest=RiskWarningWorkflowTest test
```

如果现有实现已满足测试，不改生产工作流；只有红灯证明 cursor 没有传回或唯一键不幂等时，才最小修改 `RiskWarningWorkflow`/`JdbcRiskWorkflowRepository`。

- [ ] **Step 3：增加 MySQL Testcontainers 集成测试**

沿用 `RiskMySqlMigrationIntegrationTest` 的 Docker 可用条件。真实执行 V1/V2 后写入两轮固定数据，验证：

- checkpoint JSON cursor 可读取；
- snapshot 唯一键不增长；
- evidence replace 后不重复；
- gate `enforced=false`；
- readiness SQL 与 MySQL JSON 函数兼容；
- 任意 `available_at < observed_at` 被约束拒绝。

- [ ] **Step 4：运行测试并提交**

```bash
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest='RiskWarningWorkflowTest,RiskBackfillMySqlIntegrationTest' test
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/workflow \
        stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk
git commit -m "test(risk): 验证五年回填断点与幂等"
```

## Task 9：提供运维脚本和操作文档

**Files:**

- Create: `stock-ai-rule-system-service/scripts/risk-backfill.sh`
- Create: `doc/风险模块/五年真实回填运行手册.md`
- Test: `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/RiskBackfillOperationsContractTest.java`

- [ ] **Step 1：先写契约测试**

读取脚本/文档并断言：脚本使用独立 main、非 Web 模式、三个安全开关和确认令牌环境变量；脚本不含数据库密码/Token；文档包含备份、样本、staged/full、报告、退出码、续跑和回滚查询。

- [ ] **Step 2：运行测试并确认红灯**

```bash
cd stock-ai-rule-system-service
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest=RiskBackfillOperationsContractTest test
```

- [ ] **Step 3：实现脚本与文档**

脚本只从环境读取配置并拒绝缺项：

```bash
: "${RISK_WARNING_BACKFILL_END_DATE:?必须设置回填结束交易日}"
: "${RISK_WARNING_BACKFILL_CONFIRMATION:?必须显式设置确认令牌}"

exec "${MAVEN_BIN}" -DskipTests spring-boot:run \
  -Dspring-boot.run.main-class=com.jx.tracker.risk.backfill.RiskBackfillCommandApplication \
  -Dspring-boot.run.arguments="--spring.main.web-application-type=none"
```

文档明确历史衍生网关是 80% 门控的前置条件，并给出只读验收 SQL。脚本设为可执行：

```bash
chmod +x stock-ai-rule-system-service/scripts/risk-backfill.sh
```

- [ ] **Step 4：运行测试并提交**

```bash
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest=RiskBackfillOperationsContractTest test
git add stock-ai-rule-system-service/scripts/risk-backfill.sh \
        doc/风险模块/五年真实回填运行手册.md \
        stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/RiskBackfillOperationsContractTest.java
git commit -m "docs(risk): 添加五年真实回填运行手册"
```

## Task 10：全量自动验证、代码审查和真实执行

**Files:**

- Modify only if verification finds a defect: files from Tasks 1–9
- Output (untracked runtime artifact): `stock-ai-rule-system-service/target/risk-backfill/reports/risk-backfill-*.json`
- Output (outside repository): `/Users/zengbojia/githome/stock-ai-rule-system-db-pre-risk-backfill-20260719.sql`

- [ ] **Step 1：运行后端定向测试**

```bash
cd stock-ai-rule-system-service
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -Dtest='RiskBackfill*Test,RiskRuntimeWorkflowTest,RiskWarningWorkflowTest,RiskWarningConfigurationTest' test
```

- [ ] **Step 2：运行完整后端测试**

```bash
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn test
```

Expected baseline: 至少 484 个测试，0 failures，0 errors；新增测试使总数增加，Docker 不可用时 MySQL 集成测试可显式 skip。

- [ ] **Step 3：验证普通启动不触发命令**

```bash
RISK_WARNING_ENABLED=false \
RISK_WARNING_BACKFILL_ENABLED=false \
RISK_WARNING_BACKFILL_COMMAND_ENABLED=false \
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn \
  -DskipTests spring-boot:run
```

启动日志不得出现回填运行 ID；验证后正常终止进程。

- [ ] **Step 4：按 requesting-code-review 技能审查变更**

审查重点：普通启动副作用、System.exit 可测性、报告脱敏、SQL 对象范围、复合指标覆盖、full 报告绕过、异常路径报告、幂等 cursor。

- [ ] **Step 5：备份真实数据库并校验**

使用现有本地 MySQL 连接配置执行 `mysqldump`，备份路径固定为：

```text
/Users/zengbojia/githome/stock-ai-rule-system-db-pre-risk-backfill-20260719.sql
```

随后执行：

```bash
shasum -a 256 /Users/zengbojia/githome/stock-ai-rule-system-db-pre-risk-backfill-20260719.sql
mysql --defaults-extra-file="$MYSQL_CREDENTIALS_FILE" \
  -e "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank"
```

不得把连接参数、密码或备份文件提交进 Git。

- [ ] **Step 6：先执行真实 sample**

选择本地交易日历中最近的开市日作为 `RISK_WARNING_BACKFILL_END_DATE`，配置可用 AKTools 和规范化历史衍生网关，然后：

```bash
cd stock-ai-rule-system-service
RISK_WARNING_ENABLED=true \
RISK_WARNING_BACKFILL_ENABLED=true \
RISK_WARNING_BACKFILL_COMMAND_ENABLED=true \
RISK_WARNING_BACKFILL_MODE=sample \
RISK_WARNING_BACKFILL_CONFIRMATION=BACKFILL_5Y \
./scripts/risk-backfill.sh
```

先读取 JSON 报告。只有 `unavailableDatasetCount=0`、实际覆盖 >=80%、必备维度、日期、三个周期、正式快照和影子闸门全部通过时才继续。

- [ ] **Step 7：执行 staged/full 并验证幂等**

有样本通过报告后将模式改为 `staged`（或 `full`）运行。完成后使用同一参数再运行一次，记录执行前后各风险表行数；唯一身份行数不得增长，checkpoint 可推进但不能回退。

- [ ] **Step 8：API 和前端只读冒烟**

启动服务与前端，验证：

```text
GET /api/risks/overview?horizon=1-5d
GET /api/risks/overview?horizon=5-20d
GET /api/risks/overview?horizon=20-60d
```

均返回 200；风险中心显示真实交易日和证据。数据不足的对象只显示“数据不足”，不得显示正式风险等级。

- [ ] **Step 9：最终提交**

```bash
git status --short
git add stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill \
        stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill \
        stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWarningConfiguration.java \
        stock-ai-rule-system-service/src/main/resources/application.yml \
        stock-ai-rule-system-service/scripts/risk-backfill.sh \
        doc/风险模块/五年真实回填运行手册.md
git commit -m "feat(risk): 完成五年真实风险回填命令"
```

在声称完成前，按 `verification-before-completion` 技能重新运行与最终提交对应的定向测试和完整 `mvn test`，并记录测试数、样本报告路径、数据库备份 SHA-256 和真实门控结果。
