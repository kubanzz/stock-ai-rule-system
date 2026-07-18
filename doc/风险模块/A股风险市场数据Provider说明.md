# A 股风险市场数据 Provider 说明

## 定位与边界

`MarketRiskDataProvider` 负责把 A 股市场、申万一级行业和股票的市场类原始数据规范化为风险观测，不在采集层计算 V/T/S/C/A/M 维度分、总分、风险等级或暴跌概率。所有输出仅用于辅助决策风险信号。

风险对象固定为：

- 全市场：`CN-A`；
- 申万一级行业：`SW1:<code>`，例如 `SW1:801010`；
- 股票：`000001.SZ`、`600000.SH`、`920992.BJ` 等标准 A 股代码。

行业暴露保留 `validFrom`、`validTo`、`observedAt`、`availableAt`、`source` 和 `qualityStatus`。按交易日查询时只选择当日有效且在评估时点前已经可用的记录，不使用后来发布或修订的数据。

## 数据集与指标映射

| 数据集代码 | 规范化内容 | 输出指标或元数据 | 说明 |
|---|---|---|---|
| `cn_a_stock_master` | A 股对象、名称、上市日 | `DATA_STOCK_MASTER` | 元数据，不参与风险覆盖率 |
| `sw1_membership` | 股票与申万一级行业有效期 | `DATA_SW1_MEMBERSHIP` | 元数据，不参与风险覆盖率 |
| `valuation` | PE、盈利收益率、无风险收益率 | `V1` | 同时输出估值倍数与风险溢价原始观测 |
| `market_daily` | 开收盘、成交量、基准和龙头收盘序列 | `V3`、`V4`、`C1`、`C3`、`C4`、`C5`、`A3`、`A5` | 在既有确认代理外，输出趋势/波动率降仓与目标—基准收益相关性代理 |
| `breadth` | 上涨/下跌、新高/新低、均线上方家数 | `C2`、`A4` | 输出市场宽度分量，以及明确标注为代理的日频市场深度分量，不在 Provider 内合成最终分 |
| `cross_market` | 领先资产收益、动态相关、独立市场确认数 | `S1`、`S2`、`S4` | 输出标准化收益、相关系数和跨市场确认比例 |

指标权重沿用风险框架评分卡。市场 Provider 的静态目录支持权重为 V=55、S=70、C=85、A=55，合计 265/500；A3、A4、A5 的目录权重分别为 15、20、20。跨 market 与 flow 的 26 项全局静态覆盖由集成线按完整目录验收，不能把某次运行返回的 `unavailable` 当作静态支持。

`MarketRiskCoverageReport` 只描述单个数据集在本次请求中的实际 `availability/quality`。有真实观测或业务上有效的 `valid_zero` 才计入该运行时报告；源失败与历史不足不计入可用权重。是否达到正式评分的 80% 总覆盖及 V、C、A、T/S 必备证据门槛，由风险引擎统一决定。

## A3—A5 日频代理公式

- `A3` 趋势/波动率降仓：`-distanceFromContextMovingAverage × recentToContextRealizedVolatilityRatio`。趋势距离使用 20/60/120 日上下文均线；近期和上下文实现波动率分别使用 5/20、20/60、60/120 日收益标准差。正值表示价格位于上下文均线下方，并由近期波动率相对水平放大；原始趋势距离、波动率比和公式均写入 `attributes`。
- `A4` 市场深度下降：在没有盘口历史深度时使用 `decliningCount / totalCount`、`newLowCount / totalCount`、`(totalCount - aboveMovingAverageCount) / totalCount` 三个真实日频宽度分量。每条观测都写入 `proxy=true` 和 `proxyFormula=dailyBreadthLiquidityDepth`，明确其为流动性深度代理而非买卖盘深度。
- `A5` 相关性急升：计算目标收盘与基准收盘的 5/20/60 日简单收益 Pearson 相关系数，`attributes` 标注 `pearsonCorrelationOfTargetAndBenchmarkDailyReturns`。Provider 输出原始相关系数，不在采集层判定“急升”阈值。

上述代理均沿用源记录的 `observedAt`、`availableAt`、`source` 和 `qualityStatus`。A3 至少需要上下文窗口加一个收盘点，且近期或上下文收益零方差时不出值；A5 至少需要主窗口加一个收盘点，两侧任一收益零方差时不出值。窗口不足、输入缺失或零方差时输出 `insufficient_history` 且 `value=null`，不得填充为 0。A4 的 0 仅可能来自真实计数比值为 0，仍属于真实连续观测，不表示空数据或源失败。

## 时间与质量语义

- 短、中、长周期主窗口分别为 5、20、60 个交易日；上下文窗口为 `20/60`、`60/120`、`120/250`。
- 5 年滚动基线由调用方通过请求区间提供；Provider 保留早于输出起点的上下文记录，只输出 `startDate` 至 `endDate` 内的观测。
- 日频盘后评估以 `endDate 23:59:59.999999999` 为可用截止时点，同时拒绝 `tradeDate > endDate` 的记录。
- `market_daily` 的每个目标交易日先选择该日截止前可用的最新版本，所有历史依赖再按目标记录的 `availableAt` 做 point-in-time 选择；迟到修订不得反灌更早观测。
- V3、V4、C1、C3、C4、C5、A3、A5 的主窗口、上下文窗口或相邻日窗口必须连续且全部为 `available`；任一依赖为 `stale`、`unavailable` 或 `insufficient_history`，对应派生项一律输出 `insufficient_history` 且 `value=null`。
- C3 先用最近两个连续可用点判断当日涨跌：非跌日直接输出真实 0；仅在下跌日继续要求完整成交量上下文并输出量比，上下文不足时不得把缺失量比制造为 0。
- 普通观测为 `available`；历史窗口不足为 `insufficient_history` 且 `value=null`；成功空集合为 `valid_zero`；源调用异常为 `unavailable` 并保留错误摘要。
- `observationKey` 由对象、周期、交易日、指标和原始分量构成；同一源记录重复返回时按稳定键去重，保证回填重跑幂等。`eventKey` 使用同一稳定键规则供事件型补源复用。

当前连续窗口使用上游返回的交易日集合识别缺口；如果上游整日漏传且未返回任何质量记录，Provider 无法仅凭价格序列识别该缺失日。正式接入需由上游返回完整交易日质量记录，或在集成线接入 A 股交易日历进行缺口校验。

## AKTools 接入与真实冒烟清单

`AkToolsMarketRiskSourceClient` 只依赖注入的 `MarketRiskHttpTransport`，基础 URL、超时、重试和可能的网关鉴权均由部署侧管理，代码中不保存账号、Token 或密码。固定响应测试不访问网络。

真实 AKTools 冒烟应在集成环境逐项确认以下官方 AKShare 函数对应端点及字段：

1. `stock_info_a_code_name`：A 股代码与名称；还需由交易所或其他可插拔补源补齐可靠上市日期。
2. `sw_index_first_info`：申万一级行业代码（官方形如 `801010.SI`）、PE、PB、股息率；风险溢价还需要同期无风险收益率补源，不能把股息率当作无风险收益率。
3. `index_realtime_sw(symbol="一级行业")`：申万一级实时快照，仅用于核对行业对象与最新状态，不替代历史有效期表。
4. `index_component_sw`：申万行业成分；需检查接口是否只返回当前成分。若缺少历史纳入/移除日期，必须使用带有效期的补源，不能回填为永久有效。
5. `stock_zh_index_daily`、`stock_zh_index_daily_tx` 或 `stock_zh_index_daily_em`：指数日线。真实接入需对齐复权、交易日、成交量单位，并由受控网关拼接基准和龙头序列。
6. A 股全市场快照及历史成分：用于计算上涨占比、新高新低和均线上方比例。快照接口不能伪装成历史宽度，历史不足时应明确返回 `insufficient_history`。
7. 官方 index 文档中的全球/海外指数序列：用于领先资产、动态相关与跨市场同步。仅提供近期或实时数据的接口必须配置可插拔历史补源。

本分支没有声称完成真实网络验证。最终集成验收仍需在可访问 AKTools 的环境运行上述端点，生成字段、时间跨度、空值、限频和历史覆盖报告；任何单一近期接口都不得被视为满足 5 年基线。
