# 股票规则预测系统 · 项目基础资料（仓库快照）

> 目的：供当前「股票规则预测系统」项目持续讨论、设计和开发时使用的基础资料。本文是**代码仓库快照**，不是实时行情、生产运行状态或投资建议。

| 项目 | 内容 |
|---|---|
| 源仓库 | [kubanzz/stock-ai-rule-system](https://github.com/kubanzz/stock-ai-rule-system) |
| 基准分支与提交 | `dev` · `72f45ab3c2bbf25327574659d233ba7b5447f365` |
| 整理日期 | 2026-09-27 |
| 核对方式 | 阅读设计文档、Flyway 迁移、核心后端实现、前端路由及配置；未连接部署环境或数据库，未执行测试 |
| 系统定位 | 多源数据、因子、规则、信号、风险、验证、AI 复盘与人工审核组成的**辅助决策系统**；不保证涨跌或收益 |

## 一、信息分层与使用规则

1. **代码事实**：以本页指定 commit 的源码、迁移和实际配置为准。
2. **设计目标**：设计方案和规划文档描述目标能力，不自动代表功能已上线。
3. **运行事实**：行情覆盖率、历史数据、回填完成度、预测表现、作业是否成功、模型是否真实调用，必须从实际数据库、运行日志和验收报告核对；仓库无法证明。
4. 回答后续问题时，先检查 `dev` 是否有新提交；如版本不同，说明差异后更新本页。不要把示例股票、模拟数据或文档中的预期收益当作真实结果。
5. 任何策略变化须留存数据来源、`available_at`、因子快照、规则版本、信号和回测证据。AI 提议进入候选池，经过验证与人工审核后才能发布。
6. 不把凭据、Token、数据库密码或个人持仓写进资料文件；仅记录需要的配置项名称。

## 二、架构与代码位置

| 层次 | 当前仓库位置 | 已见到的实现 |
|---|---|---|
| 设计与契约 | `doc/股票因子规则预测与 AI 规则优化系统设计方案.md`、`docs/contracts/stock-ai-rule-contracts.md`、`AGENTS.md` | 辅助决策边界、模块蓝图、API/枚举约定 |
| 前端 | `stock-ai-rule-system-ui/apps/web-antd/src/` | Vue 3 / Vben Admin 页面与股票业务 API；信号看板、风险中心、股票研究、规则治理、回测报告、AI 复盘、候选规则、运行中心 |
| 后端 | `stock-ai-rule-system-service/src/main/java/com/jx/tracker/` | JDK 21、Spring Boot 3.5.15、MyBatis-Plus；采集、因子、JSON/Drools 规则、信号、回测、AI、风险与调度 |
| 数据库 | `stock-ai-rule-system-service/src/main/resources/db/migration/V1__baseline.sql` 至 `V4__risk_observation_storage_tiers.sql` | MySQL/Flyway；核心业务表与风险证据/快照/冷热分层 |
| 风险衍生网关 | `infra/risk-data-gateway/src/risk_gateway/` | 历史行情、宽度、估值、行业关系、跨市场等衍生数据集接口 |

业务链路：**数据同步 → 技术因子 → 规则推理/信号生成 → 风险评估及影子闸门 → 历史验证 → AI 复盘 → 候选规则回测 → 人工审核与发布**。`DailyWorkflowOrchestrator` 有对应步骤与运行记录；规则推理和信号生成在实际有序步骤中合并执行。

## 三、数据输入与质量语义

| 类别 | 入口及现状 | 重要边界 |
|---|---|---|
| 股票基础与日线 | `MarketDataProvider` 支持 AKTools、Tushare、CSV、Mock；交易日历、股票列表和日 K 同步有 API | 配置默认行情源是 `aktools`，回退类型是 `mock`；回退结果必须标识来源，不能当作真实行情 |
| 风险市场数据 | 风险 Provider 及衍生网关含 `market_daily`、`breadth`、`valuation`、`sw1_membership`、`cross_market` | 历史完整度、交易日连续性与 `observedAt/availableAt` 须核查；当期行业成分不能冒充历史持仓关系 |
| 资金流和事件 | 保证金、ETF、公告、业绩预告、解禁与减持等适配器及接入说明 | ETF 现货订单流只是代理，不等于申赎事实；无事件的有效零值与源失败必须区分 |
| 仓库内真实样本 | 未见提交的 `.csv/.parquet/.db/.sqlite/.jsonl/.xlsx` 行情样本 | 仓库主要是**接入与处理代码**，不是已装载的五年行情数据库 |

质量状态中 `available`、`valid_zero`、`unavailable`、`insufficient_history` 等语义不可混用。回填和回测都应按评估时点选择当时已可用的数据，杜绝未来数据泄漏。风险资料明确：部分日频分量为代理指标；缺失历史、接口报错或窗口不足时不得造出零值。实际覆盖以运行报告为准。

## 四、核心表与关系

| 数据域 | 主要表 | 核心用途 |
|---|---|---|
| 股票与采集 | `stock_base`、`stock_daily_quote`、`trade_calendar`、`market_data_sync_run`、`stock_watchlist`、`stock_watchlist_item` | 股票池、日线、交易日、同步运行与自选池 |
| 因子与规则 | `stock_factor_daily`、`rule_definition`、`rule_version`、`rule_operation_log` | 按股票/交易日的因子 JSON、规则内容及其版本/操作审计 |
| 信号与验证 | `stock_signal_daily`、`stock_signal_daily_history`、`stock_actual_result` | 信号方向、评分、命中规则、解释、版本历史与后验实际表现 |
| 复盘与回测 | `ai_review_report`、`candidate_rule`、`backtest_result` | 归因、候选规则、回测结果和审核状态 |
| 作业 | `workflow_run`、`workflow_step_run` | 运行参数、步骤状态、耗时与错误 |
| 风险 | `risk_object_exposure`、`risk_indicator_observation`、`risk_event_fact`、`risk_score_snapshot`、`risk_score_evidence`、`risk_gate_result`、`risk_ingestion_checkpoint`、`risk_indicator_baseline`、`risk_indicator_observation_archive` | 行业暴露、带时点的观测、事件、评分、证据、建议闸门、断点、轻量历史与归档 |

关键字段示例：`stock_daily_quote` 保存 `symbol/trade_date/OHLC/pre_close/volume/amount/change_pct/data_source`；`stock_factor_daily` 保存 `symbol/trade_date/factor_json`；`stock_signal_daily` 包含分数、置信度、命中规则和解释，V2 迁移引入独立 `signal_direction` 与历史版本；风险观测、证据和评分保存对象、周期、交易日、`observed_at`、`available_at`、来源及质量状态。字段和约束的最终依据为 V1—V4 SQL，不以设计文档中的旧表结构替代。

## 五、规则、信号与风险

- `JsonRuleEngineExecutor` 支持 `eq/ne/gt/gte/lt/lte/in` 条件；另有 `DroolsRuleEngineExecutor`。只有符合状态与格式的规则参与执行。
- `SignalScoringService` 按多空分数给出 `bullish/bearish/watch` 方向；历史兼容的 `high_risk` 值仍存在。新版风险流程强调**方向和风险分开记录**，不要仅用一个“高风险”标签覆盖方向事实。
- 独立风险框架对市场、行业、个股及 1–5d、5–20d、20–60d 周期评估。静态目录有 V（结构脆弱性）、T（实质触发）、S（外部传导）、C（本地确认）、A（被动卖出）共 26 项指标；目录支持不等于某次运行的真实覆盖。
- `ShadowRiskGate` 目前只给出 `NORMAL/NOTICE/DOWNGRADE/BLOCK` **建议**，输出 `enforced=false`，不会实际改写正式信号；是否启用正式门控需另行设计、验证与发布。
- 候选规则、单规则回测、规则版本、人工发布和回滚存在对应服务。发布逻辑校验候选规则状态、回测和人工审核，不允许 AI 直接上线。

## 六、回测与 AI 的当前边界

- `SingleRuleBacktestService` 是**单规则/候选规则**回测，读取历史信号、实际表现，部分情形用后续日线回退，考虑费率和滑点，保存胜率、收益、回撤、夏普等结果；代码支持的持有期收益字段为 1/3/5/10 日。不能据此宣称全策略、多资产组合、样本外验证或实盘收益已经达标。
- `AiReviewServiceImpl` 可形成报告和候选规则。只有配置真实 `ai.llm.base-url` 与 `ai.llm.api-key` 时默认选择 HTTP LLM；否则使用 `MockLlmClient`。每份复盘应标注模型与输入来源，不能把 Mock 内容当成真实 AI 效果。
- 设计方案提到模拟盘、多源新闻/财报/宏观和更完整的策略评估；本次静态阅读未核实其端到端落地。仓库未发现独立的 `paper_trade` 实现路径，不能声称模拟盘已经运行。

## 七、自动化与部署状态

- `DailyWorkflowScheduledTask` 具备交易日后调度入口，但 `DAILY_WORKFLOW_ENABLED` 默认 **false**；人工触发请求默认 `dryRun=true`。生产自动运行必须配置并检查每一步记录。
- 风险模块默认开启配置与实际有数据/可评分是两件事；五年回填命令默认关闭，需真实源、数据迁移、样本门控和报告验收。风险冷分层读取及归档开关默认关闭。
- 此快照未读取部署数据库、实时报价、最近工作流、回测报告或运行日志；**不能给出当前命中率、准确率、真实覆盖率或“已具备盈利能力”的结论**。

## 八、后续迭代时优先验证的事实

1. 部署环境 `Flyway` V1—V4、服务启动、前后端联通及实际数据源；记录提交号和环境。
2. 股票、交易日与日线真实覆盖，区分 AKTools/Tushare/CSV/Mock，报告缺失、异常与更新延迟。
3. 五年风险回填的样本门控报告、三个周期正式快照覆盖和 `available_at` 时点审计。
4. 日常工作流是否在真实交易日完整执行、失败恢复和幂等重跑。
5. 回测口径：交易成本、幸存者偏差、复权与停牌、样本外与市场阶段分层；保存原始参数和版本。
6. 真实 LLM 复盘的可重复性与候选规则审核、发布、回滚证据；决定是否实现模拟盘及正式风险门控。

## 九、核对入口

- [设计方案](https://github.com/kubanzz/stock-ai-rule-system/blob/72f45ab3c2bbf25327574659d233ba7b5447f365/doc/%E8%82%A1%E7%A5%A8%E5%9B%A0%E5%AD%90%E8%A7%84%E5%88%99%E9%A2%84%E6%B5%8B%E4%B8%8E%20AI%20%E8%A7%84%E5%88%99%E4%BC%98%E5%8C%96%E7%B3%BB%E7%BB%9F%E8%AE%BE%E8%AE%A1%E6%96%B9%E6%A1%88.md)
- [基础契约](https://github.com/kubanzz/stock-ai-rule-system/blob/72f45ab3c2bbf25327574659d233ba7b5447f365/docs/contracts/stock-ai-rule-contracts.md)
- [数据库迁移目录](https://github.com/kubanzz/stock-ai-rule-system/tree/72f45ab3c2bbf25327574659d233ba7b5447f365/stock-ai-rule-system-service/src/main/resources/db/migration)
- [行情数据 Provider](https://github.com/kubanzz/stock-ai-rule-system/tree/72f45ab3c2bbf25327574659d233ba7b5447f365/stock-ai-rule-system-service/src/main/java/com/jx/tracker/market/data/provider)
- [工作流](https://github.com/kubanzz/stock-ai-rule-system/tree/72f45ab3c2bbf25327574659d233ba7b5447f365/stock-ai-rule-system-service/src/main/java/com/jx/tracker/scheduler)
- [风险市场数据说明](https://github.com/kubanzz/stock-ai-rule-system/blob/72f45ab3c2bbf25327574659d233ba7b5447f365/doc/%E9%A3%8E%E9%99%A9%E6%A8%A1%E5%9D%97/A%E8%82%A1%E9%A3%8E%E9%99%A9%E5%B8%82%E5%9C%BA%E6%95%B0%E6%8D%AEProvider%E8%AF%B4%E6%98%8E.md)
- [五年回填手册](https://github.com/kubanzz/stock-ai-rule-system/blob/72f45ab3c2bbf25327574659d233ba7b5447f365/doc/%E9%A3%8E%E9%99%A9%E6%A8%A1%E5%9D%97/%E4%BA%94%E5%B9%B4%E7%9C%9F%E5%AE%9E%E5%9B%9E%E5%A1%AB%E8%BF%90%E8%A1%8C%E6%89%8B%E5%86%8C.md)
