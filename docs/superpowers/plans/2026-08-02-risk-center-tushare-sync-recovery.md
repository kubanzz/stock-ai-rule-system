# 风险中心 TuShare 同步恢复实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `test-driven-development` 逐任务实现；每项修复必须先看到目标测试按预期失败，再编写最小实现。

**目标：** 恢复市场同步中的行业、宽度和跨市场采集，使 TuShare 返回的部分有效申万成员可以入库，并在当前成员接口失败时复用数据库中仍然有效的行业关系。

**架构：** 保留 TuShare 主源、AKTools/衍生网关补源和现有 80% 正式评估门槛。修复只发生在同步编排、TuShare 行业成员边界和工作流历史行业读取三个位置；融资、ETF、估值仅在真实探针证明现有校验错误时调整，不以降低质量标准换取覆盖率。

**技术栈：** JDK 21、Spring Boot 3.5.15、JUnit 5、AssertJ、Mockito、MyBatis/Spring JDBC、Maven。

---

## 文件结构

- 修改 `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWorkflowPlanner.java`：恢复市场级 `breadth`、`cross_market` 调度。
- 修改 `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/runtime/RiskWorkflowPlannerTest.java`：锁定市场同步的七类数据集。
- 修改 `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/market/TushareMarketRiskSourceClient.java`：隔离无申万一级分类的成员行，保留有效行并输出可审计的部分历史状态。
- 修改 `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/data/market/TushareMarketRiskSourceClientTest.java`：覆盖市场宽表中空 `l1_code/l1_name` 的真实边界。
- 修改 `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/workflow/JdbcRiskWorkflowRepository.java`：市场级任务没有股票对象时读取有效行业关系。
- 修改 `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/workflow/JdbcRiskWorkflowRepositoryTest.java`：覆盖市场级历史行业复用。
- 按真实失败定位结果选择性修改 `TushareFlowEventSourceClient.java` 及其测试，不预先放宽资金数据质量标准。

### 任务 1：恢复市场宽度与跨市场任务

- [ ] **步骤 1：编写失败的编排测试**

将 `marketPlanCollectsTheCurrentMembershipUniverseInOnePagedRequest` 的期望改为七个任务，并明确要求：

```java
assertThat(plan.collectionTasks()).hasSize(7);
assertThat(plan.collectionTasks()).anyMatch(task ->
        task.datasetCode().equals(MarketDatasetCode.BREADTH.code()));
assertThat(plan.collectionTasks()).anyMatch(task ->
        task.datasetCode().equals(MarketDatasetCode.CROSS_MARKET.code()));
```

- [ ] **步骤 2：验证测试因任务缺失失败**

运行：

```bash
cd stock-ai-rule-system-service
mvn -q -Dtest=RiskWorkflowPlannerTest test
```

预期：`marketPlan...` 因任务数为 5 且找不到 `breadth/cross_market` 失败。

- [ ] **步骤 3：最小恢复两个市场任务**

在 `planMarket()` 中追加：

```java
tasks.add(task(MarketRiskDataProvider.PROVIDER_CODE,
        MarketDatasetCode.BREADTH.code(), List.of(market)));
tasks.add(task(MarketRiskDataProvider.PROVIDER_CODE,
        MarketDatasetCode.CROSS_MARKET.code(), List.of(market)));
```

- [ ] **步骤 4：再次运行编排测试，预期全部通过**

### 任务 2：允许申万成员批次保留有效记录

- [ ] **步骤 1：编写失败的 TuShare 映射测试**

构造一个包含一条有效成员和一条空行业分类记录的市场级响应，要求结果不抛异常、保留有效成员，并标为 `INSUFFICIENT_HISTORY`：

```java
assertThat(result.qualityStatus())
        .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
assertThat(result.records()).singleElement();
assertThat(result.failureReason()).contains("missing SW1 classification", "1");
```

- [ ] **步骤 2：运行目标测试，确认当前实现因空行业编码失败**

运行：

```bash
cd stock-ai-rule-system-service
mvn -q -Dtest=TushareMarketRiskSourceClientTest test
```

- [ ] **步骤 3：最小实现成员行分类**

在映射前将行拆分为有效和无分类两组。只有字段完整且代码格式符合 `ts_code`、`l1_code`、`in_date` 契约的行进入 `membership(row)`；无分类行计数进入 `failureReason`。有效记录存在时返回部分历史，全部无效时返回历史不足，不把脏行解释为零成员。

- [ ] **步骤 4：运行目标测试，预期全部通过**

### 任务 3：市场同步复用有效历史行业关系

- [ ] **步骤 1：编写失败的仓储测试**

保存一条 `valid_from <= endDate`、`valid_to` 为空且 `available_at <= asOf` 的行业关系，使用只含 `market:CN-A` 的手动市场请求读取，要求返回该关系：

```java
List<IndustryExposure> result = repository.findIndustryExposures(marketRequest);
assertThat(result).singleElement().satisfies(exposure ->
        assertThat(exposure.sector().objectId()).isEqualTo("SW1:801120"));
```

- [ ] **步骤 2：运行仓储测试，确认当前实现返回空列表**

运行：

```bash
cd stock-ai-rule-system-service
mvn -q -Dtest=JdbcRiskWorkflowRepositoryTest test
```

- [ ] **步骤 3：实现市场级查询分支**

当请求对象没有股票但明确包含 `market:CN-A` 时，按日期和 `available_at` 查询全部有效股票行业关系；其他无股票请求仍返回空列表。复用现有 `mapExposure`，不改变股票级分块路径。

- [ ] **步骤 4：运行仓储和工作流测试，预期通过**

### 任务 4：复核资金与估值失败

- [ ] **步骤 1：使用当前本地配置执行最小真实探针**

仅查询最近开放日所需的 `daily_basic`、`margin`、`margin_detail`、`etf_basic`、`fund_share`、`fund_nav`、`fund_daily`，记录 API 名、行数和缺失字段，不输出 Token 或原始业务数据。

- [ ] **步骤 2：为被证明是映射错误的场景补失败测试**

如果响应存在有效记录但适配器整批失败，则先把最小响应固化为单元测试；如果接口确实返回空集或日期滞后，则保持 `INSUFFICIENT_HISTORY/UNAVAILABLE`，不伪造观测。

- [ ] **步骤 3：实现最小映射修复并运行对应测试**

融资汇总以官方 `margin` 为事实，明细仅用于可验证分解；ETF 只接受份额、净值和行情三者可对齐的数据；估值必须保持 point-in-time 语义。

### 任务 5：回归与实时验收

- [ ] **步骤 1：运行目标回归测试**

```bash
cd stock-ai-rule-system-service
mvn -q -Dtest=RiskWorkflowPlannerTest,TushareMarketRiskSourceClientTest,JdbcRiskWorkflowRepositoryTest,RiskWarningWorkflowTest,MarketRiskDataProviderTest,TushareFlowEventSourceClientTest test
```

- [ ] **步骤 2：运行完整后端测试和编译**

```bash
cd stock-ai-rule-system-service
mvn test
```

- [ ] **步骤 3：重启服务后触发一次市场同步**

验收条件：同步任务不因无行业分类记录整批失败；最新交易日至少生成市场三个周期快照；有当前或仍有效历史行业关系时生成行业快照；接口状态准确报告仍然不可用的数据集。

- [ ] **步骤 4：检查变更边界并合并到 `dev`**

运行 `git diff --check`、`git status --short`，只提交风险模块和本计划文件，不提交 `application.yml`、日志、Token 或 Turbo 缓存。
