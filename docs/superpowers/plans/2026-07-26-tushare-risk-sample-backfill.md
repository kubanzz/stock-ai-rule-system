# TuShare 风险样本回填实施计划

> **执行要求：** 使用 `executing-plans` 与 `test-driven-development`，每个任务先写失败测试，再做最小实现；任何真实 Token 都不得进入 Git、日志、异常或测试快照。

**目标：** 将 TuShare 作为风险结构化数据主源接入现有风险回填骨架，保留 CNInfo/AKTools 公告补源，先支持确定性 50 股样本及 80% 正式评估门控，再由现有 `staged` 模式扩展全 A 股。

**架构：** 新增独立的 TuShare 风险 HTTP 边界，将列式响应归一化为字段映射；市场与资金/事件适配器继续输出既有 `MarketSourceBatch`、`FlowEventSourceBatch`，避免修改工作流与持久化契约。配置通过 `RiskWarningProperties` 选择主源；回填预检根据主源探测 TuShare 所需 API。真实 Token 只保存在 Git 忽略的 `application-local.yml`。

**技术栈：** JDK 21、Spring Boot 3.5.15、Spring `RestClient`、Jackson、JUnit 5、AssertJ、MockRestServiceServer、Maven。

---

## 任务 1：安全配置与数据源选择

**文件：**

- 修改：`.gitignore`
- 修改：`stock-ai-rule-system-service/src/main/resources/application.yml`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWarningProperties.java`
- 测试：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/runtime/RiskWarningPropertiesTest.java`
- 本地忽略文件：`stock-ai-rule-system-service/config/application-local.yml`

**步骤：**

1. 写失败测试，覆盖默认 `aktools`、规范化 `tushare`、非法主源、启用 TuShare 但 Token 缺失时 fail-fast。
2. 运行 `mvn -Dtest=RiskWarningPropertiesTest test`，确认测试因缺少 source 配置能力失败。
3. 在 `RiskWarningProperties` 增加 `source.primary`、`source.tushareEnabled`、`source.cninfoAnnouncementFallbackEnabled`，并提供严格校验方法。
4. 在公共 `application.yml` 增加环境变量占位，不写 Token。
5. 在 `.gitignore` 精确忽略后端外部 `config/application-local.yml`；不得放入 `src/main/resources`，避免密钥进入构建产物。
6. 默认激活 `dev,local`，创建外部 `config/application-local.yml`，将用户授权的 Token 写入 `stock-ai-rule.market-data.provider.token`，并启用 TuShare 风险主源；禁止打印文件内容。
7. 运行属性测试，并用 `git check-ignore`、`git status` 验证本地配置未被跟踪。
8. 提交可跟踪文件，不提交本地配置。

## 任务 2：TuShare 风险 HTTP 边界

**文件：**

- 新增：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/tushare/TushareRiskRequest.java`
- 新增：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/tushare/TushareRiskResponse.java`
- 新增：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/tushare/TushareRiskException.java`
- 新增：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/tushare/TushareRiskHttpClient.java`
- 测试：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/data/tushare/TushareRiskHttpClientTest.java`

**步骤：**

1. 写失败测试，覆盖请求体的 `api_name/params/fields`、列数组转行、成功空集、字段缺失、权限错误、限频错误和异常脱敏。
2. 运行目标测试，确认因类不存在或行为缺失而失败。
3. 实现不可变请求/响应模型和 `RestClient` 客户端；构造函数严格要求 HTTPS 远程 URL、非空 Token。
4. 错误只包含 API 名、错误码和供应商消息，不包含请求体或 Token；`toString` 不得携带 Token。
5. 对 TuShare 频率限制执行有界重试，测试使用零等待策略避免真实 sleep。
6. 运行目标测试和现有 `TushareMarketDataProviderTest`。
7. 提交任务 2。

## 任务 3：TuShare 市场风险源与主/补源切换

**文件：**

- 新增：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/market/FallbackMarketRiskSourceClient.java`
- 新增：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/market/TushareMarketRiskSourceClient.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWarningConfiguration.java`
- 测试：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/data/market/FallbackMarketRiskSourceClientTest.java`
- 测试：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/data/market/TushareMarketRiskSourceClientTest.java`
- 修改测试：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/runtime/RiskWarningConfigurationTest.java`

**步骤：**

1. 写失败测试：补源仅在 `UNAVAILABLE` 或 `INSUFFICIENT_HISTORY` 时触发，成功结果和成功空结果不补源。
2. 最小实现 `FallbackMarketRiskSourceClient`，保留主源失败原因和最终 source。
3. 写 TuShare 映射失败测试，至少覆盖：
   - `stock_basic` → `StockMasterPoint`；
   - `index_member_all` 的 `in_date/out_date` → `IndustryExposure`；
   - `daily/index_daily` → `MarketDailyPoint`；
   - `daily_basic` → `ValuationPoint`；
   - 历史截面聚合 → `BreadthPoint`；
   - `index_global` → `CrossMarketPoint`；
   - `availableAt >= observedAt`、请求窗口过滤、历史不足。
4. 实现市场适配器；所有衍生定义和版本写入审计字段，不能将当前行业成员当作历史成员。
5. 修改 Spring 配置：`tushare` 模式以 TuShare 为主、AKTools 为补源；默认模式保持原行为。
6. 运行三个目标测试类及 `MarketRiskDataProviderTest`。
7. 提交任务 3。

## 任务 4：TuShare 资金/事件风险源与 CNInfo 公告兜底

**文件：**

- 新增：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/flow/TushareFlowEventSourceClient.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/runtime/RiskWarningConfiguration.java`
- 测试：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/data/flow/TushareFlowEventSourceClientTest.java`
- 修改测试：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/data/flow/CompositeFlowEventSourceClientTest.java`
- 修改测试：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/runtime/RiskWarningConfigurationTest.java`

**步骤：**

1. 写失败测试，覆盖：
   - `margin/margin_detail` → `margin_financing`；
   - `fund_share/fund_nav/fund_daily` → `etf_fund_flow`；
   - `forecast_vip` → `earnings_forecast`；
   - `share_float` → `share_unlock`；
   - `stk_holdertrade` → `share_reduction`；
   - `stock_announcement` 不请求 TuShare 公告接口，而交由 CNInfo/AKTools；
   - 成功零事件不触发补源。
2. 实现适配器，保留发布时间/公告日作为 `availableAt`，所有代理公式写入 attributes。
3. 在 TuShare 主源模式使用 `CompositeFlowEventSourceClient`：结构化数据优先 TuShare，公告直接路由到 CNInfo/AKTools；结构化主源不可用或历史不足才补源。
4. 运行目标测试及 `FlowEventRiskDataProviderTest`。
5. 提交任务 4。

## 任务 5：TuShare 权限预检与真实契约冒烟

**文件：**

- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillSourceProbe.java`
- 新增：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/TushareRiskBackfillSourceProbe.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillPreflightService.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillPreflight.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/backfill/RiskBackfillCommandConfiguration.java`
- 测试：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/TushareRiskBackfillSourceProbeTest.java`
- 修改测试：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/backfill/RiskBackfillPreflightServiceTest.java`
- 新增：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/data/TushareRiskContractSmokeTest.java`

**步骤：**

1. 写失败测试：TuShare 主源必须探测所需 API 的最小窗口，任一必需 API 无权限时预检失败，结果不得泄露 Token。
2. 实现探测器和预检路由；默认 AKTools 预检保持兼容。
3. 增加默认跳过的真实契约测试，仅在 `RISK_TUSHARE_IT=true` 且 Token 存在时运行；只记录 API 名、行数和日期边界。
4. 使用本地配置执行真实最小权限探测；不运行全量五年回填，除非本地数据库、交易日历和外部公告源预检全部就绪。
5. 运行目标测试，提交任务 5。

## 任务 6：回归、密钥审计与合并

**文件：**

- 修改：`docs/superpowers/specs/2026-07-26-risk-tushare-sample-backfill-design.md`（仅在实现产生必要偏差时）

**步骤：**

1. 运行：
   - `mvn -Dtest='*Tushare*,*RiskWarningConfigurationTest,*RiskBackfillPreflightServiceTest,*MarketRiskDataProviderTest,*FlowEventRiskDataProviderTest' test`
   - `mvn test`
2. 搜索 64 位十六进制密钥模式，确认 Git 跟踪文件、测试报告和日志没有真实 Token。
3. 检查 `git diff --check`、`git status --short` 和分支提交历史。
4. 执行规格审查和代码质量审查，修复所有必须项。
5. 按用户授权，将功能分支以非破坏方式合并回 `dev`；保留原工作区缓存和日志改动。
6. 在 `dev` 上再次运行关键测试并报告：已实现能力、真实探测结果、未执行的全量步骤及原因。
