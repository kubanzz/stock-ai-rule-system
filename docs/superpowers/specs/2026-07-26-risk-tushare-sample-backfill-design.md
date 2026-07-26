# TuShare 风险样本回填与全市场扩展设计

## 1. 背景与目标

当前风险中心已有市场、申万一级行业和少量个股快照，但最新短周期快照完整度只有约 41%，所有对象都处于暂定或数据不足状态。正式风险等级要求完整度至少 80%，因此总览中的正常、关注、预警和严重对象均为 0。

本次目标是引入 TuShare 作为风险结构化数据主源，继续使用 CNInfo 公告作为公告事件兜底，先让 50 只确定性代表样本通过五年真实回填门控，再按同一模型、结束日和数据契约扩展到全部活跃 A 股。

风险结果继续定位为辅助决策信号，不表示事件发生概率，不构成投资建议，也不保证收益。

## 2. 成功标准

50 只样本必须同时满足：

- 26 项指标实际加权覆盖率至少 80%；
- V、C、A 三个维度分别达到 60%，且 T 或 S 至少一个维度达到 60%；
- 五年评分窗口至少包含 1200 个市场交易日；
- 至少 80% 样本股票覆盖核心行情交易日的 95%；
- `1-5d`、`5-20d`、`20-60d` 三个周期均存在正式市场快照；
- 结束日至少 80% 样本股票同时具备三个周期正式快照；
- 正式快照完整度不低于 80%，数据不足记录不得伪造正式等级；
- 所有 `availableAt >= observedAt`；
- 所有风险闸门继续保持 `enforced=false`；
- 相同参数重跑保持幂等，失败任务可以从 checkpoint 续跑。

只有样本门控全部通过，`staged` 模式才允许扩展全市场。

## 3. 范围

### 3.1 本次实现

- 新增风险专用 TuShare HTTP 客户端，不复用只支持代码表、交易日历和日线的通用 `TushareMarketDataProvider`。
- 使用 TuShare 获取股票与基金基础信息、日线、每日指标、估值、融资融券、基金份额、财务与业绩预告、申万历史成员、国际指数和期权日线。
- 保留现有 AKTools/CNInfo 公告链路作为公告事件主源或兜底源。
- 将 TuShare 接入市场类与资金/事件类风险 Provider。
- 为 `V2`、`V6`、`S3`、`C6` 增加可审计的结构化指标映射；`S5` 使用带发布时间的公告信息。
- 复用现有 `sample/staged/full` 五年回填命令、覆盖门控、JSON 报告、checkpoint 和幂等写入能力。
- 增加 TuShare 权限探测与 50 股样本真实契约冒烟。
- Token 只从环境变量读取，不写入 Git、源码、日志、报告或异常信息。

### 3.2 不在本次范围

- 不降低 80% 正式评估门槛；
- 不把分钟数据引入当前日频风险模型；
- 不用 mock、未来数据、事后修订或人工补零提高覆盖率；
- 不在样本门控失败时直接运行全市场回填；
- 不自动启用强制风险闸门；
- 不把 ETF 二级市场成交或实时 IOPV 冒充历史申购赎回事实；
- 不把当前行业成员反灌为历史成员。

## 4. 方案选择

### 方案 A：仅替换通用行情 Provider

只把普通股票日线切换到已有 `TushareMarketDataProvider`。改动最少，但风险工作流仍固定使用 AKTools 风险客户端，融资、ETF、申万历史、跨市场和事件数据都不会进入风险快照，无法达到 80%。

### 方案 B：TuShare 风险主源 + CNInfo 公告兜底

新增风险专用 TuShare 适配器，结构化数据优先使用 TuShare，公告事件继续使用 CNInfo；主源不可用或历史不足时按数据集切换补源。该方案不依赖 TuShare 独立公告权限，能够先验证 50 股样本，且保留多源故障隔离能力。

### 方案 C：TuShare 全量单一数据源

结构化数据和公告全部使用 TuShare。运维边界最简单，但必须额外购买独立公告权限；单一供应商故障会同时影响行情和事件，不适合作为首个样本阶段。

采用已确认的方案 B。历史分钟权限不参与当前实现。

## 5. 数据源与指标映射

| 风险输入 | TuShare API | 目标指标 | 质量约束 |
|---|---|---|---|
| 股票、交易日、复权日线 | `stock_basic`、`trade_cal`、`daily`、`adj_factor` | `V3/V4/C1/C3/C4/C5/A3/A5` | 连续交易日窗口完整，复权基准固定 |
| 每日估值与无风险利率 | `daily_basic`、国内利率/国债收益率 | `V1` | PE 必须为正；利率按当时已发布值对齐 |
| 盈利预测与财务预期 | `forecast_vip`、`fina_indicator_vip` | `V2/T1` | 使用公告日或实际披露日，不按报告期提前可用 |
| 融资融券 | `margin`、`margin_detail` | `V5/A1/T3` | 保留余额、买入、偿还和变化率 |
| 日线流动性和资金流 | `daily_basic`、`moneyflow` | `V6` | 仅使用可复现的换手、成交和资金流代理 |
| 国际指数 | `index_global` | `S1/S2/S4` | 至少三个领先市场序列完整 |
| 申万历史成员 | `index_member_all` | `S3` 与行业暴露 | 必须使用 `in_date/out_date` 形成有效期 |
| 全市场日线截面 | `daily`、`daily_basic` | `C2/A4` | 按历史可交易股票池计算宽度，不使用当前股票池回填 |
| 期权日线 | `opt_basic`、`opt_daily` | `C6` | 使用当日可得合约，保留到期和流动性筛选 |
| ETF 份额、净值和行情 | `fund_share`、`fund_nav`、`fund_daily` | `A2` | 由份额变化与参考资产计算日频净变化，标明衍生公式 |
| 公告与事件 | CNInfo/AKTools；TuShare 公告仅作为可选补源 | `T1/T2/T3/T4/S5` | 进入受控经济含义字典；成功无事件为 `valid_zero` |
| 解禁与股东增减持 | `share_float`、`stk_holdertrade` | `M` 修饰项 | 不直接提高基础完整度 |

每个派生指标都必须写入 `source`、原始字段、公式版本、`observedAt`、`availableAt`、样本数和质量状态。代理指标不得伪装为原始事实。

## 6. 架构设计

### 6.1 配置与凭据

继续使用：

```yaml
stock-ai-rule:
  market-data:
    provider:
      type: ${MARKET_DATA_PROVIDER:aktools}
      token: ${MARKET_DATA_PROVIDER_TOKEN:${TUSHARE_TOKEN:}}
      api-url: ${MARKET_DATA_PROVIDER_API_URL:https://api.tushare.pro}
```

风险模块新增数据源选择：

```yaml
stock-ai-rule:
  risk-warning:
    source:
      primary: ${RISK_WARNING_PRIMARY_SOURCE:aktools}
      tushare-enabled: ${RISK_WARNING_TUSHARE_ENABLED:false}
      cninfo-announcement-fallback-enabled: ${RISK_WARNING_CNINFO_ANNOUNCEMENT_FALLBACK_ENABLED:true}
```

`TUSHARE_TOKEN` 缺失时，启用 TuShare 必须 fail-fast，禁止静默降级到 mock。错误信息只能说明 Token 缺失或权限不足，不得包含 Token 值。

### 6.2 TuShare HTTP 边界

新增 `TushareRiskHttpClient`，只负责：

- 构造 TuShare `api_name/token/params/fields` 请求；
- 解析 `code/msg/data.fields/data.items`；
- 对分页或按日期分片调用执行限速、重试和退避；
- 将权限不足、频率限制、空数据和网络失败归一为明确结果；
- 对日志和异常进行 Token 脱敏。

业务客户端不得直接处理 TuShare 列数组响应。

### 6.3 市场风险源

新增 `TushareMarketRiskSourceClient implements MarketRiskSourceClient`，把 TuShare 结构化响应转换为现有 `MarketSourceRecord`：

- `cn_a_stock_master`：`stock_basic`；
- `sw1_membership`：`index_member_all`；
- `market_daily`：`daily + adj_factor + daily_basic`；
- `valuation`：`daily_basic + 国内利率`；
- `breadth`：按历史交易日股票池聚合日线截面；
- `cross_market`：`index_global`；
- 新增期权、预期、流动性和资金映射所需的规范化数据集。

新增 `FallbackMarketRiskSourceClient`：TuShare 为主源；仅当 TuShare 返回 `unavailable` 或 `insufficient_history` 时调用 AKTools/衍生网关；成功空结果不触发补源。

### 6.4 资金与事件风险源

新增 `TushareFlowEventSourceClient implements FlowEventSourceClient`：

- `margin_financing`：`margin/margin_detail`；
- `etf_fund_flow`：`fund_share/fund_nav/fund_daily`；
- `earnings_forecast`：`forecast_vip/fina_indicator_vip`；
- `share_unlock`：`share_float`；
- `share_reduction`：`stk_holdertrade`。

`stock_announcement` 继续由现有 CNInfo 客户端负责。TuShare 客户端加入 `CompositeFlowEventSourceClient` 的补源列表，但没有独立公告权限时不得请求 `anns_d`。

### 6.5 评分与适用性

正式评分仍按 500 总权重计算，不因某数据源缺失重新分配权重。新增的 `V2/V6/S3/S5/C6` 必须使用现有指标目录权重。

市场、行业和股票分别计算自身可适用证据。个股三层合成继续使用市场 25%、行业 35%、个股 40%，只有各层自身形成正式快照后才参与正式合成。不得通过继承同一条证据在多层重复提高覆盖率。

## 7. 五年样本回填流程

1. 读取固定模型版本和结束交易日。
2. 权限探测只请求每类 API 的最小窗口，不写业务表。
3. 使用现有确定性算法选择 50 只股票，固定样本列表和 SHA-256。
4. 采集窗口为评分开始日前六年到结束日，评分窗口为最近五年。
5. 按 21～50 只分块采集，TuShare 请求按 API 限制进一步按日期或代码切片。
6. 每个数据集独立保存 checkpoint；成功数据幂等 upsert。
7. 计算三个周期的市场、行业、股票快照。
8. 生成 JSON 覆盖报告，列出 26 项指标、五维覆盖、对象覆盖、时序错误和失败数据集。
9. 门控失败时退出码为 5，不运行全市场。
10. 门控通过后，`staged` 模式按相同配置扩展全 A 股。

## 8. 错误与降级

- Token 缺失或无效：预检失败，禁止回填。
- 单个 API 权限不足：标记数据集 `unavailable`，报告精确 API 名，不切换到 mock。
- TuShare 频率限制：使用带抖动的指数退避；达到上限后保存 checkpoint 并失败退出。
- TuShare 历史不足：已有记录仅供审计；只有满足请求起始日和 point-in-time 语义才进入正式评分。
- CNInfo 公告失败：公告数据集 `unavailable`；不得把无响应解释为零事件。
- ETF 份额缺口：A2 保持 `insufficient_history`，不使用成交额替代。
- 期权无有效合约：C6 为 `valid_zero` 仅限交易所当日确实无可用合约且查询成功，否则为历史不足。
- 全市场阶段局部分块失败：停止本次扩展，保持已完成分块和 checkpoint，使用相同参数续跑。

## 9. 测试策略

### 9.1 单元测试

- TuShare 列数组解析、字段缺失、空数据、错误码和 Token 脱敏；
- API 请求切片、频率限制、重试和 checkpoint；
- 每个 TuShare 数据集到风险记录的字段、时间和质量映射；
- `V2/V6/S3/S5/C6` 公式、方向、权重和边界；
- 主源/补源切换只发生在不可用或历史不足；
- 公告成功零事件不触发补源；
- Token 缺失时 fail-fast；
- 80%/60% 门控和正式/暂定边界。

### 9.2 契约测试

- 默认跳过真实 TuShare 网络调用；
- 显式设置 `RISK_TUSHARE_IT=true` 和环境 Token 才运行；
- 每个所需 API 只取最小窗口并输出行数、最早日期、最新日期和质量，不输出原始数据或 Token；
- 50 股样本先运行 `sample`，报告通过后才允许 `staged`。

### 9.3 回归验证

- 后端相关单元测试和 Maven 编译；
- MySQL 回填集成测试；
- 相同样本参数重复运行后唯一身份行数不增加；
- 三个风险总览接口返回 200；
- 风险中心正式计数与数据库正式快照聚合一致；
- 暂定评估仍不会进入正式等级或风险闸门。

## 10. 部署与密钥管理

- 开发、测试和生产均通过 `TUSHARE_TOKEN` 或受控密钥系统注入；
- `.env`、IDE 私有运行配置和本地密钥文件必须被 Git 忽略；
- JSON 报告、日志、异常、测试快照和数据库 payload 禁止保存 Token；
- 首次接入后轮换已经通过聊天或其他非密钥通道传递的 Token；
- 生产扩全市场前完成数据库备份和恢复演练；
- 公司或商业使用时按 TuShare 对机构账号和数据许可的要求购买相应权限。

## 11. 实施顺序

1. 凭据配置、错误脱敏和权限探测；
2. TuShare HTTP 客户端；
3. 日线、估值、申万、国际指数市场源；
4. 融资、ETF、财务与事件源；
5. `V2/V6/S3/S5/C6` 指标接入；
6. 50 股真实契约冒烟；
7. `sample` 五年回填与门控修复；
8. 样本幂等重跑；
9. `staged` 全市场扩展；
10. 风险中心和数据库最终验收。
