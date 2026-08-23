# 风险中心评分读模型与冷热分层实现计划

> 执行方式：使用 `executing-plans`，每个任务先写失败测试，再做最小实现并单独提交。

**目标：** 将风险中心从多年原始观测扫描切换到持久化评分读模型，并提供可分阶段启用的紧凑基线、两年热数据和冷归档能力。

**技术栈：** JDK 21、Spring Boot 3.5、JdbcTemplate/MyBatis、Flyway、MySQL 8、Vue 3、TypeScript、Vitest。

## 任务 1：风险中心只读取评分读模型

**修改：**

- `RiskAssessmentQueryServiceImpl.java`
- `RiskAssessmentQueryServiceImplTest.java`
- 删除仅由页面查询使用的 `ProvisionalRiskEvidenceProvider`、JDBC 实现、原始观测 Mapper/Row 与对应测试。

**步骤：**

1. 修改查询服务测试，使服务仅依赖快照、证据和暴露 Mapper；增加不完整快照只能使用已落库证据的断言。
2. 运行 `mvn -Dtest=RiskAssessmentQueryServiceImplTest test`，确认旧构造函数导致测试失败。
3. 删除查询时暂定证据加载和合并逻辑，只批量加载 `risk_score_evidence`。
4. 删除无调用方的查询时原始历史组件。
5. 运行 `mvn -Dtest='RiskAssessmentQueryServiceImplTest,ProvisionalRiskAssessmentCalculatorTest' test`。

**完成标准：** 风险中心查询包中不存在从原始观测表补算页面结果的调用链，相关测试通过。

## 任务 2：建立紧凑基线和冷归档结构

**新增/修改：**

- `V4__risk_observation_storage_tiers.sql`
- `RiskMigrationContractTest.java`
- `RiskStorageTierProperties.java`
- `RiskWarningConfiguration.java`
- `application.yml`

**步骤：**

1. 先在迁移契约测试中要求 V4 包含 `risk_indicator_baseline`、`risk_indicator_observation_archive`、自然唯一键和评分/归档索引。
2. 运行 `mvn -Dtest=RiskMigrationContractTest test`，确认 V4 尚不存在。
3. 新增只建结构、不搬迁数据的 V4 迁移。
4. 新增分层配置：默认关闭分层读取和自动归档，热数据保留 2 年，批次大小受限。
5. 重跑迁移契约测试。

**完成标准：** Flyway 可安全升级，迁移本身不执行生产数据删除或大批量回填。

## 任务 3：采集双写与评分分层读取

**修改：**

- `JdbcRiskWorkflowRepository.java`
- `JdbcRiskWorkflowRepositoryTest.java`

**步骤：**

1. 新增测试：观测写入同时更新热表和基线；重复写幂等；较旧修订不覆盖较新基线。
2. 新增测试：开启分层读取后，单日评分读取基线；多日回填合并热表和冷表。
3. 运行目标测试并确认失败。
4. 将热表与基线双写放入同一事务，基线只保留自然键最新版本和实际数值。
5. 按请求类型和配置选择评分数据源；默认关闭时保持现有行为。
6. 运行 `mvn -Dtest=JdbcRiskWorkflowRepositoryTest test`。

**完成标准：** 新数据持续维护紧凑基线，启用开关后的读取路由符合设计，关闭开关完全兼容旧行为。

## 任务 4：安全的批量归档能力与运维说明

**新增：**

- `RiskObservationStorageTierService.java`
- `RiskObservationStorageTierServiceTest.java`
- `RiskObservationStorageTierScheduledTask.java`
- `docs/operations/risk-observation-storage-tiering.md`

**步骤：**

1. 写测试约束截止日期、批次上限、先复制校验再删除以及重复执行幂等。
2. 实现显式批次归档服务；每批在事务内压缩基线、复制冷表、校验并删除热表。
3. 增加默认关闭的定时入口，每次运行限制最大批次数，避免长事务。
4. 编写初始化、校验、启用、观察和回滚步骤；不在开发过程中操作现有数据库数据。
5. 运行归档目标测试和 SQL 解析测试。

**完成标准：** 未启用时没有数据搬迁；启用后只处理截止日前的有界批次，并具有恢复说明。

## 任务 5：降低风险中心首屏数据量

**修改：**

- `risk-center-state.ts`
- `risk-center-state.test.ts`
- 必要的风险中心 UI 契约测试。

**步骤：**

1. 将测试期望改为股票默认每页 20 条，并保持一级行业完整加载。
2. 运行风险中心 Vitest，确认测试失败。
3. 修改默认查询和分页展示中的回退值。
4. 运行 `pnpm exec vitest run --dom apps/web-antd/src/views/stock/risk/center apps/web-antd/src/api/stock/risk/risk-contract.test.ts`。

**完成标准：** 首屏最多请求 20 条股票数据，行业矩阵与分页交互不受影响。

## 任务 6：全量验证与审查

1. 后端运行 `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test`。
2. 前端运行目标 Vitest、`pnpm lint` 和 `pnpm check`；若全仓已有无关失败，记录精确范围并补充目标文件验证。
3. 运行 `git diff --check`、检查迁移文件和配置默认值。
4. 使用 `requesting-code-review` 与 `verification-before-completion` 做交付前审查。
5. 使用 `finishing-a-development-branch` 汇总分支状态和集成选项。

