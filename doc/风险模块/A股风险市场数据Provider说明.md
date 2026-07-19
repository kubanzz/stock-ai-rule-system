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

`MarketRiskCoverageReport` 只描述单个数据集在指定 `object + horizon + tradeDate + asOf` 评估元组上的实际 `availability/quality`，不得跨对象、周期或历史日期选择“最强质量”。有真实观测或业务上有效的 `valid_zero` 才计入该运行时报告；源失败与历史不足不计入可用权重。复合指标必须具备全部必需分量：V1 为 `peTtm + riskPremium`，C2 为三个宽度分量，C5 为趋势距离与开盘缺口，A4 为三个深度代理分量。是否达到正式评分的 80% 总覆盖及 V、C、A、T/S 必备证据门槛，由风险引擎统一决定。

## A3—A5 日频代理公式

- `A3` 趋势/波动率降仓：`-distanceFromContextMovingAverage × recentToContextRealizedVolatilityRatio`。趋势距离使用 20/60/120 日上下文均线；近期和上下文实现波动率分别使用 5/20、20/60、60/120 日收益标准差。正值表示价格位于上下文均线下方，并由近期波动率相对水平放大；原始趋势距离、波动率比和公式均写入 `attributes`。
- `A4` 市场深度下降：在没有盘口历史深度时使用 `decliningCount / totalCount`、`newLowCount / totalCount`、`(totalCount - aboveMovingAverageCount) / totalCount` 三个真实日频宽度分量。每条观测都写入 `proxy=true` 和 `proxyFormula=dailyBreadthLiquidityDepth`，明确其为流动性深度代理而非买卖盘深度。
- `A5` 相关性急升：计算目标收盘与基准收盘的 5/20/60 日简单收益 Pearson 相关系数，`attributes` 标注 `pearsonCorrelationOfTargetAndBenchmarkDailyReturns`。Provider 输出原始相关系数，不在采集层判定“急升”阈值。

上述代理均沿用源记录的 `observedAt`、`availableAt`、`source` 和 `qualityStatus`。A3 至少需要上下文窗口加一个收盘点，且近期或上下文收益零方差时不出值；A5 至少需要主窗口加一个收盘点，两侧任一收益零方差时不出值。窗口不足、输入缺失或零方差时输出 `insufficient_history` 且 `value=null`，不得填充为 0。A4 的 0 仅可能来自真实计数比值为 0，仍属于真实连续观测，不表示空数据或源失败。

## 时间与质量语义

- 短、中、长周期主窗口分别为 5、20、60 个交易日；上下文窗口为 `20/60`、`60/120`、`120/250`。
- 5 年滚动基线由调用方通过请求区间提供；Provider 保留早于输出起点的上下文记录，只输出 `startDate` 至 `endDate` 内的观测。
- 日频盘后评估以 `endDate 23:59:59.999999999` 为可用截止时点，同时拒绝 `tradeDate > endDate` 的记录。
- `market_daily` 与 `cross_market` 的每个目标交易日先选择该日截止前可用的最新版本，所有历史依赖再按目标记录的 `availableAt` 做 point-in-time 选择；迟到修订不得反灌更早观测，S1 的派生 `availableAt` 为全部输入的最大可用时点。
- V3、V4、C1、C3、C4、C5、A3、A5 的主窗口、上下文窗口或相邻日窗口必须连续且全部为 `available`；任一依赖为 `stale`、`unavailable` 或 `insufficient_history`，对应派生项一律输出 `insufficient_history` 且 `value=null`。
- S1 的标准化窗口同样必须全部为 `available`；短、中、长周期分别复用 60、120、250 点基线尾窗。
- C3 先用最近两个连续可用点判断当日涨跌：非跌日直接输出真实 0；仅在下跌日继续要求完整成交量上下文并输出量比，上下文不足时不得把缺失量比制造为 0。
- 普通观测为 `available`；历史窗口不足为 `insufficient_history` 且 `value=null`；市场、估值、宽度和行业暴露的成功空集合仍为 `insufficient_history`，不能把“没有取到行情”解释为业务零值；源调用异常为 `unavailable` 并保留错误摘要。`valid_zero` 只允许出现在有真实输入且计算结果确为 0 的指标分量上。
- `observationKey` 由对象、周期、交易日、指标和原始分量构成；同一源记录重复返回时按稳定键去重，保证回填重跑幂等。`eventKey` 使用同一稳定键规则供事件型补源复用。
- 同一对象、交易日、`observedAt`、`availableAt` 出现内容不同的修订时，由于缺少可确定排序的版本号，Provider 显式拒绝该批次并返回 `unavailable`；不得依赖上游返回顺序任选一条。

五年回填按对象预建 `tradeDate → sorted revisions` 索引，每个目标日只选择请求周期需要的最大尾窗，并在三个周期间复用；复杂度受 `交易日数 × 最大窗口` 约束，不为每个周期回扫全历史或重复构建日期映射。

当前连续窗口使用上游返回的交易日集合识别缺口；如果上游整日漏传且未返回任何质量记录，Provider 无法仅凭价格序列识别该缺失日。正式接入需由上游返回完整交易日质量记录，或在集成线接入 A 股交易日历进行缺口校验。

## AKTools 接入与真实冒烟清单

`AkToolsMarketRiskSourceClient` 区分“AKTools 原生端点”和“规范化衍生网关”。原生基础 URL 使用 `RISK_WARNING_AKTOOLS_BASE_URL`；可选衍生网关使用 `RISK_WARNING_DERIVED_GATEWAY_BASE_URL`。基础 URL、超时、重试和网关鉴权均由部署侧管理，代码中不保存账号、Token 或密码。固定响应测试不访问网络。

原生端点按部署依赖锁定的 AKShare 1.18.64 / AKTools 0.0.91 公开函数签名调用；已对照 AKShare 1.18.64 官方 wheel 源码核验下列函数参数：

| 用途 | 原生端点与参数 | 时间边界 |
|---|---|---|
| A 股代码表 | `stock_info_a_code_name()`，无参数 | 当前抓取快照；`observedAt/availableAt` 为真实抓取时点，不允许回填成历史快照 |
| 申万一级目录 | `sw_index_first_info()`，无参数 | 当前目录与当前估值字段，包括 `TTM(滚动)市盈率` |
| 申万成分 | `index_component_sw(symbol=<行业代码>)` | 当前成分；`计入日期` 可作为 `validFrom`，但没有移除历史时不能伪造历史有效期 |

原生函数不接受统一的 `start_date/end_date/objects/cursor`。五年历史、对齐序列及 point-in-time 元数据必须由衍生网关提供：

| 数据集 | 衍生网关路径 |
|---|---|
| 历史申万有效期 | `/api/risk/sw1-membership` |
| 对齐后的目标/基准/龙头日线 | `/api/risk/market-daily` |
| 估值与无风险收益率 | `/api/risk/valuation` |
| 历史市场宽度 | `/api/risk/breadth` |
| 跨市场传导 | `/api/risk/cross-market` |

衍生请求使用 `start_date`、`end_date`、带类型的 `objects`（如 `market:CN-A,stock:600519.SH`）及可选 `cursor`。每条响应必须显式返回 `objectType`、`objectId`、`tradeDate`、`observedAt`、`availableAt` 和数据集字段；时间戳支持本地 ISO 时间及带偏移 ISO 时间。响应 `meta` 必须保留 `historyComplete`、`insufficientHistory`、`earliestAvailableDate`、`historyGapReason` 和可选 `nextCursor`。`earliestAvailableDate` 缺失也视为历史不完整；只有 `historyComplete=true` 且最早日期覆盖请求起点时才允许推进 `nextCursor`。部分记录可以审计保存，但对应观测降为 `insufficient_history`，原始值写入 `auditValue`，正式评分只读取 `available/valid_zero`；当前申万 exposure 可继续用于当期对象映射。空数组不代表市场值为 0，而是 `insufficient_history`。

真实 AKTools 冒烟应在集成环境逐项确认以下官方 AKShare 函数对应端点及字段：

1. `stock_info_a_code_name`：A 股代码与名称；还需由交易所或其他可插拔补源补齐可靠上市日期。
2. `sw_index_first_info`：申万一级行业代码（官方形如 `801010.SI`）、PE、PB、股息率；风险溢价还需要同期无风险收益率补源，不能把股息率当作无风险收益率。
3. `index_component_sw(symbol="801010")`：申万行业成分；当前适配器不会传入 `start_date/end_date/objects/cursor`。没有衍生网关的日常五年窗口请求仍会保存本次抓取的当前暴露，使用 `计入日期` 作为 `validFrom`、真实抓取时刻作为 `observedAt/availableAt`、`validTo=null`；批次整体保持 `insufficient_history`、记录失败原因且不推进 checkpoint，因此该快照不能反灌历史评分。若要满足历史覆盖，必须使用带完整有效期的衍生网关。
4. `stock_zh_index_daily(symbol="sh000001")`：原始指数日线只提供单序列。真实接入需对齐复权、交易日、成交量单位，并由受控网关拼接目标、基准和龙头序列。
5. `stock_zh_a_spot_em()`：仅为 A 股全市场当前快照，不能伪装成历史宽度。
6. `index_global_spot_em()`：仅为跨市场当前快照，不能代替领先资产、动态相关及独立市场确认的历史对齐序列。

仓库现已提供 `infra/risk-data-gateway` 免费数据衍生网关。网关固定保留 `tradeDate/observedAt/availableAt/fetchedAt`，并在 `market_daily` 中把沪深 300 和上证 50 分别记录为 `benchmarkDefinition=CSI300`、`leaderDefinition=SSE50`，同时写入 `proxy=true`。这些字段只说明代理定义与血缘，不改变评分公式，也不把风险分解释为暴跌概率。

当前免费源的客观边界必须原样保留：

- `stock_industry_clf_hist_sw` 依赖申万官网文件；TLS/502 或文件结构异常时不关闭证书校验，只返回带真实抓取时间的当前成分，历史请求保持不足；
- 百度个股 PE 的长区间会降采样，不能把稀疏的“全部”序列补成逐交易日估值；PE 或 10 年国债收益率缺失时 V1 不出正式值；
- ETF 免费接口只有净值、成交和申赎状态，没有历史真实申赎份额/净额，A2 固定返回 `free_source_has_no_historical_etf_redemption_fact`，不得以净值或订单流替代；
- 跨市场篮子固定为标普 500、纳斯达克、恒生和日经 225；单源失败可在至少三个市场时继续，少于三个或 60 日相关窗口不足时返回历史不足；
- 历史市场宽度使用“当前上市且存在当日历史行情”的股票集合，明确标记 `currentListedStocksWithObservableHistoricalBars` 和 `proxy=true`，不声称拥有已退市股票的完整历史成分。

因此，网关健康只表示进程、缓存和 AKTools 可连接，不表示 80% 正式覆盖已经通过。最终资格必须以样本回填报告为准；任何单一近期接口都不得被视为满足 5 年基线。

仓库提供默认跳过的原生端点和衍生网关真实契约冒烟测试。先启动容器：

```bash
docker-compose -f docker-compose.market-data.yml up -d --build aktools risk-data-gateway
docker-compose -f docker-compose.market-data.yml ps
```

网关缓存位于 Docker 卷 `risk-data-gateway-cache`。查看健康和运行网关单测：

```bash
curl -fsS http://127.0.0.1:18090/health
docker build --target test -t stock-risk-data-gateway-test infra/risk-data-gateway
docker run --rm --security-opt seccomp=unconfined stock-risk-data-gateway-test pytest -q
```

缓存损坏会自动隔离到卷内 `quarantine`。确需全部重建时先停止网关，再备份或删除该命名卷并重新启动；缓存不是 MySQL 业务备份。旧版 Docker Engine 运行 Python 3.12 线程时需要 Compose 中的 `seccomp:unconfined`，端口仍只绑定 `127.0.0.1`。

连接已部署的 AKTools 与网关后执行：

```bash
RISK_AKTOOLS_IT=true \
RISK_AKTOOLS_BASE_URL=http://127.0.0.1:8090 \
mvn -Dtest=AkToolsContractSmokeTest test
```

```bash
RISK_DERIVED_GATEWAY_IT=true \
RISK_WARNING_DERIVED_GATEWAY_BASE_URL=http://127.0.0.1:18090 \
mvn -Dtest=AkToolsContractSmokeTest test
```

可通过 `RISK_AKTOOLS_STOCK_SYMBOL`、`RISK_AKTOOLS_SW1_SYMBOL`、`RISK_AKTOOLS_INDEX_SYMBOL`、`RISK_AKTOOLS_SMOKE_END_DATE`、`RISK_AKTOOLS_REPORT_DATE` 和 `RISK_DERIVED_GATEWAY_SMOKE_DATE` 覆盖样本。衍生冒烟只打印端点、行数、最早日期和质量状态，不打印完整响应。
