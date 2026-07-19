# 免费数据风险衍生网关实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 新增可审计、可缓存的风险衍生网关，把 AKTools 免费原始数据规范化为现有 Java Risk Provider 可消费的历史接口，并完成真实样本回填验证。

**架构：** 独立 Python/FastAPI 容器通过 HTTP 调用 AKTools，使用 Parquet 分区缓存原始及规范化序列，按保守 point-in-time 策略输出五类市场数据；ETF 申赎缺失时返回明确的历史不足。Spring Boot 保持评分、门控和业务持久化权威，仅做必要的契约与审计属性兼容。

**技术栈：** Python 3.12、FastAPI 0.139.0、Pydantic 2.13.4、pandas 3.0.3、PyArrow 22.0.0、pytest 9.0.2、Docker Compose、Java 21、Spring Boot 3.5.15、JUnit 5。

---

## 文件结构

新增网关文件：

- `infra/risk-data-gateway/Dockerfile`：固定运行时和 test/runtime 两个镜像阶段。
- `infra/risk-data-gateway/requirements.txt`：固定生产依赖。
- `infra/risk-data-gateway/requirements-test.txt`：固定测试依赖。
- `infra/risk-data-gateway/src/risk_gateway/__init__.py`：包入口。
- `infra/risk-data-gateway/src/risk_gateway/config.py`：环境变量和限制配置。
- `infra/risk-data-gateway/src/risk_gateway/models.py`：查询、响应和覆盖元数据模型。
- `infra/risk-data-gateway/src/risk_gateway/aktools.py`：AKTools HTTP 客户端、重试和字段校验。
- `infra/risk-data-gateway/src/risk_gateway/cache.py`：Parquet 分区与 JSON 清单的原子缓存。
- `infra/risk-data-gateway/src/risk_gateway/time_policy.py`：交易日、时区和可用时间策略。
- `infra/risk-data-gateway/src/risk_gateway/series.py`：代码规范化、行情列转换和序列对齐。
- `infra/risk-data-gateway/src/risk_gateway/datasets/market_daily.py`：目标/基准/领涨篮子日线。
- `infra/risk-data-gateway/src/risk_gateway/datasets/valuation.py`：估值与无风险收益率分量。
- `infra/risk-data-gateway/src/risk_gateway/datasets/breadth.py`：全市场历史宽度。
- `infra/risk-data-gateway/src/risk_gateway/datasets/cross_market.py`：跨市场对齐原始分量。
- `infra/risk-data-gateway/src/risk_gateway/datasets/membership.py`：申万行业有效期。
- `infra/risk-data-gateway/src/risk_gateway/datasets/etf.py`：A2 明确历史不足响应。
- `infra/risk-data-gateway/src/risk_gateway/app.py`：HTTP 路由和异常映射。
- `infra/risk-data-gateway/tests/`：与上述职责一一对应的 pytest。

修改现有文件：

- `docker-compose.market-data.yml`：增加网关服务、健康检查和缓存卷。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/market/MarketDailyPoint.java`：保留基准和领涨序列定义元数据。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/market/AkToolsMarketRiskSourceClient.java`：解析网关审计字段。
- `stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/market/MarketRiskDataProvider.java`：将审计字段写入风险观测 attributes，不改变公式。
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/data/market/AkToolsMarketRiskSourceClientTest.java`：网关契约测试。
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/data/market/MarketRiskDataProviderTest.java`：代理审计属性测试。
- `stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/data/AkToolsContractSmokeTest.java`：真实网关冒烟。
- `doc/风险模块/A股风险市场数据Provider说明.md`：补充网关运行、代理定义和已验证接口。
- `doc/风险模块/五年真实回填运行手册.md`：补充启动、缓存和样本回填命令。

### 任务 1：网关骨架、配置与健康检查

**文件：**
- 创建：`infra/risk-data-gateway/requirements.txt`
- 创建：`infra/risk-data-gateway/requirements-test.txt`
- 创建：`infra/risk-data-gateway/Dockerfile`
- 创建：`infra/risk-data-gateway/src/risk_gateway/__init__.py`
- 创建：`infra/risk-data-gateway/src/risk_gateway/config.py`
- 创建：`infra/risk-data-gateway/src/risk_gateway/app.py`
- 测试：`infra/risk-data-gateway/tests/test_health.py`

- [ ] **步骤 1：编写健康检查失败测试**

```python
def test_health_reports_version_and_dependencies(client, fake_aktools):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {
        "status": "up",
        "version": "1",
        "aktools": "up",
        "cache": "up",
    }
```

- [ ] **步骤 2：构建 test 阶段并确认测试失败**

运行：

```bash
docker build --target test -t stock-risk-data-gateway-test infra/risk-data-gateway
docker run --rm stock-risk-data-gateway-test pytest tests/test_health.py -q
```

预期：FAIL，`risk_gateway.app` 尚不存在。

- [ ] **步骤 3：实现固定依赖、配置和应用工厂**

`requirements.txt` 固定：

```text
fastapi==0.139.0
pandas==3.0.3
pyarrow==22.0.0
pydantic==2.13.4
requests==2.34.2
uvicorn==0.51.0
```

`config.py` 提供不可变 `Settings`，读取 `RISK_GATEWAY_AKTOOLS_BASE_URL`、`RISK_GATEWAY_CACHE_DIR`、`RISK_GATEWAY_TIMEOUT_SECONDS`、`RISK_GATEWAY_MAX_RETRIES`、`RISK_GATEWAY_MAX_CONCURRENCY` 和 `RISK_GATEWAY_PAGE_SIZE`；页面大小限制为 `1..5000`，并发限制为 `1..16`。

`app.py` 暴露 `create_app(settings, client=None, cache=None)` 和模块级 `app`。健康检查分别探测 AKTools `/version` 和缓存目录读写；任一依赖失败时返回 HTTP 503 与脱敏错误代码。

- [ ] **步骤 4：运行测试并确认通过**

运行：`docker build --target test -t stock-risk-data-gateway-test infra/risk-data-gateway && docker run --rm stock-risk-data-gateway-test pytest tests/test_health.py -q`

预期：PASS。

- [ ] **步骤 5：提交**

```bash
git add infra/risk-data-gateway
git commit -m "feat(risk): 搭建衍生数据网关"
```

### 任务 2：查询契约、质量元数据与稳定分页

**文件：**
- 创建：`infra/risk-data-gateway/src/risk_gateway/models.py`
- 创建：`infra/risk-data-gateway/src/risk_gateway/pagination.py`
- 测试：`infra/risk-data-gateway/tests/test_models.py`
- 测试：`infra/risk-data-gateway/tests/test_pagination.py`

- [ ] **步骤 1：编写对象解析与 cursor 失败测试**

```python
def test_query_normalizes_and_sorts_objects():
    query = RiskQuery.from_raw("20210719", "20260717", "stock:600519.SH,market:CN-A")
    assert query.object_keys == ("market:CN-A", "stock:600519.SH")

def test_cursor_rejects_other_request_hash():
    cursor = encode_cursor("hash-a", 50)
    with pytest.raises(InvalidCursor):
        decode_cursor(cursor, "hash-b")
```

- [ ] **步骤 2：运行测试确认失败**

运行：`docker build --target test -t stock-risk-data-gateway-test infra/risk-data-gateway && docker run --rm stock-risk-data-gateway-test pytest tests/test_models.py tests/test_pagination.py -q`

预期：FAIL，模型和分页模块尚不存在。

- [ ] **步骤 3：实现严格查询与响应模型**

实现：

```python
class HistoryMeta(BaseModel):
    historyComplete: bool
    insufficientHistory: bool
    earliestAvailableDate: date | None
    historyGapReason: str | None
    nextCursor: str | None
    source: str
    sourceVersion: str
    calculationVersion: str
    fetchedAt: datetime

class GatewayResponse(BaseModel):
    data: list[dict[str, object]]
    meta: HistoryMeta
```

`RiskQuery` 只接受 `market:CN-A`、`sector:SW1:<6 位数字>` 和标准股票代码，拒绝开始日晚于结束日、超过十一年加 31 日的请求、重复/空对象和非法 cursor。cursor 使用 URL-safe Base64 编码的 `requestHash:offset`，请求哈希不匹配立即返回 400。

- [ ] **步骤 4：运行测试确认通过**

运行同步骤 2；预期全部 PASS。

- [ ] **步骤 5：提交**

```bash
git add infra/risk-data-gateway/src/risk_gateway/models.py \
  infra/risk-data-gateway/src/risk_gateway/pagination.py \
  infra/risk-data-gateway/tests/test_models.py \
  infra/risk-data-gateway/tests/test_pagination.py
git commit -m "feat(risk): 固化衍生网关查询契约"
```

### 任务 3：AKTools 客户端与原子 Parquet 缓存

**文件：**
- 创建：`infra/risk-data-gateway/src/risk_gateway/aktools.py`
- 创建：`infra/risk-data-gateway/src/risk_gateway/cache.py`
- 测试：`infra/risk-data-gateway/tests/test_aktools.py`
- 测试：`infra/risk-data-gateway/tests/test_cache.py`

- [ ] **步骤 1：编写重试、字段漂移和缓存损坏测试**

```python
def test_client_retries_503_then_returns_array(fake_session):
    fake_session.responses = [response(503), response(200, [{"日期": "2026-07-17"}])]
    assert AkToolsClient(settings, fake_session).get("stock_zh_a_hist", {})[0]["日期"] == "2026-07-17"

def test_cache_quarantines_hash_mismatch(tmp_path):
    cache = ParquetCache(tmp_path)
    cache.put(partition("market_daily", "600519.SH", 2026), frame())
    corrupt_cached_file(tmp_path)
    assert cache.get(partition("market_daily", "600519.SH", 2026)) is None
    assert list((tmp_path / "quarantine").iterdir())
```

- [ ] **步骤 2：运行测试确认失败**

运行：`docker build --target test -t stock-risk-data-gateway-test infra/risk-data-gateway && docker run --rm stock-risk-data-gateway-test pytest tests/test_aktools.py tests/test_cache.py -q`

预期：FAIL。

- [ ] **步骤 3：实现客户端和缓存**

`AkToolsClient.get(function, params)` 调用 `/api/public/<function>`；只重试连接错误、429、502、503、504，退避为 `0.5s/1s/2s` 且受最大重试配置限制。200 响应必须是 JSON 数组；错误摘要只包含函数名、状态码和错误类型。

`ParquetCache` 使用 `CachePartition(dataset, object_key, year)`，规范化路径并拒绝 `..`。写入流程为同目录临时文件 → 读取校验行数/schema → SHA-256 → `os.replace`；JSON manifest 同样原子替换。读取时校验哈希，失败文件移动到 `quarantine`，不向调用方返回部分数据。

- [ ] **步骤 4：运行测试确认通过**

运行同步骤 2；预期全部 PASS。

- [ ] **步骤 5：提交**

```bash
git add infra/risk-data-gateway/src/risk_gateway/aktools.py \
  infra/risk-data-gateway/src/risk_gateway/cache.py \
  infra/risk-data-gateway/tests/test_aktools.py \
  infra/risk-data-gateway/tests/test_cache.py
git commit -m "feat(risk): 增加原始数据缓存与重试"
```

### 任务 4：时间策略、代码规范化和序列对齐

**文件：**
- 创建：`infra/risk-data-gateway/src/risk_gateway/time_policy.py`
- 创建：`infra/risk-data-gateway/src/risk_gateway/series.py`
- 测试：`infra/risk-data-gateway/tests/test_time_policy.py`
- 测试：`infra/risk-data-gateway/tests/test_series.py`

- [ ] **步骤 1：编写 point-in-time 与对齐失败测试**

```python
def test_daily_market_value_is_available_after_close():
    point = market_times(date(2026, 7, 17))
    assert point.observed_at.isoformat() == "2026-07-17T15:00:00+08:00"
    assert point.available_at.isoformat() == "2026-07-17T15:30:00+08:00"

def test_global_close_is_not_assigned_before_it_is_known():
    aligned = align_global_to_a_share(us_close("2026-07-17T16:00:00-04:00"), cn_calendar())
    assert aligned.trade_date == date(2026, 7, 20)
```

- [ ] **步骤 2：运行测试确认失败**

运行：`docker build --target test -t stock-risk-data-gateway-test infra/risk-data-gateway && docker run --rm stock-risk-data-gateway-test pytest tests/test_time_policy.py tests/test_series.py -q`

预期：FAIL。

- [ ] **步骤 3：实现版本化时间策略与序列校验**

策略版本固定为 `cn-a-pit-v1`：A 股行情当日 15:30 可用；估值、融资和只有日期的公告在下一开市日 09:00 可用；精确公告时间保留原时间；全球行情在原市场收盘后映射到下一个尚未开盘的 A 股交易日。所有输出校验 `observedAt <= availableAt`。

`series.py` 规范化沪深京股票、申万行业和市场代码，统一 AKTools 中文列名为 `date/open/close/volume/amount`，拒绝重复日期不同值、非正价格、负成交量和缺口日期。序列连接只使用两侧都可用的交易日。

- [ ] **步骤 4：运行测试确认通过**

运行同步骤 2；预期全部 PASS。

- [ ] **步骤 5：提交**

```bash
git add infra/risk-data-gateway/src/risk_gateway/time_policy.py \
  infra/risk-data-gateway/src/risk_gateway/series.py \
  infra/risk-data-gateway/tests/test_time_policy.py \
  infra/risk-data-gateway/tests/test_series.py
git commit -m "feat(risk): 实现历史时间与序列策略"
```

### 任务 5：行情与估值数据集

**文件：**
- 创建：`infra/risk-data-gateway/src/risk_gateway/datasets/__init__.py`
- 创建：`infra/risk-data-gateway/src/risk_gateway/datasets/market_daily.py`
- 创建：`infra/risk-data-gateway/src/risk_gateway/datasets/valuation.py`
- 测试：`infra/risk-data-gateway/tests/test_market_daily.py`
- 测试：`infra/risk-data-gateway/tests/test_valuation.py`

- [ ] **步骤 1：编写规范输出和历史不足测试**

```python
def test_market_daily_emits_java_contract(dataset_context):
    response = MarketDailyDataset(dataset_context).fetch(query_for("stock:600519.SH"))
    row = response.data[-1]
    assert set(("objectType", "objectId", "tradeDate", "open", "close", "volume",
                "benchmarkClose", "leaderClose", "observedAt", "availableAt")) <= row.keys()
    assert row["benchmarkDefinition"] == "CSI300"
    assert row["leaderDefinition"] == "SSE50"

def test_valuation_requires_pe_and_risk_free_yield(dataset_context):
    response = ValuationDataset(dataset_context_without_bond()).fetch(query_for("stock:600519.SH"))
    assert response.meta.historyComplete is False
    assert response.meta.historyGapReason == "risk-free yield history is incomplete"
```

- [ ] **步骤 2：运行测试确认失败**

运行：`docker build --target test -t stock-risk-data-gateway-test infra/risk-data-gateway && docker run --rm stock-risk-data-gateway-test pytest tests/test_market_daily.py tests/test_valuation.py -q`

预期：FAIL。

- [ ] **步骤 3：实现行情规范化**

股票使用 `stock_zh_a_hist(period=daily, adjust=qfq)`；市场和基准使用指数日线；申万行业使用 `index_hist_sw(period=day)`。统一基准为沪深 300，领涨确认序列第一阶段使用上证 50，并在每行输出定义及 `proxy=true`。任何目标、基准或领涨序列缺少请求区间时，整批标记历史不足，不填补交易日价格。

- [ ] **步骤 4：实现估值规范化**

股票使用 `stock_zh_valuation_baidu(indicator=市盈率(TTM), period=全部)`；无风险收益率使用 `bond_zh_us_rate` 的中国国债收益率，按上一已披露值向后最多保持五个 A 股交易日。`peTtm <= 0` 不构造风险溢价，记录质量为历史不足。行业和市场只有在可从当日有效成分形成不少于 20 个样本的中位数 PE 时才返回正式值，否则返回历史不足。

- [ ] **步骤 5：运行测试确认通过**

运行同步骤 2；预期全部 PASS。

- [ ] **步骤 6：提交**

```bash
git add infra/risk-data-gateway/src/risk_gateway/datasets \
  infra/risk-data-gateway/tests/test_market_daily.py \
  infra/risk-data-gateway/tests/test_valuation.py
git commit -m "feat(risk): 提供历史行情与估值接口"
```

### 任务 6：全市场历史宽度

**文件：**
- 创建：`infra/risk-data-gateway/src/risk_gateway/datasets/breadth.py`
- 测试：`infra/risk-data-gateway/tests/test_breadth.py`

- [ ] **步骤 1：编写横截面计算测试**

```python
def test_breadth_uses_only_observable_active_stocks():
    response = BreadthDataset(context_with_six_stocks()).fetch(query_for("market:CN-A"))
    row = response.data[-1]
    assert row["advancingCount"] == 2
    assert row["decliningCount"] == 3
    assert row["newHighCount"] == 1
    assert row["newLowCount"] == 1
    assert row["aboveMovingAverageCount"] == 2
    assert row["totalCount"] == 5
```

- [ ] **步骤 2：运行测试确认失败**

运行：`docker build --target test -t stock-risk-data-gateway-test infra/risk-data-gateway && docker run --rm stock-risk-data-gateway-test pytest tests/test_breadth.py -q`

预期：FAIL。

- [ ] **步骤 3：实现宽度构建器**

按日使用当时已上市且有前一交易日收盘的股票：上涨/下跌比较前收；新高/新低使用过去 250 个有效交易点（不足 60 点不参与高低统计）；均线上方使用 20 日简单均线（不足 20 点不计入分子或分母）。停牌或缺值股票当日不进入 `totalCount`。输出记录写入 `breadthDefinition=advanceDecline-250dHighLow-20dMA-v1`。

只有请求区间所有开市日都有横截面、每日至少 1000 只有效股票且缓存 manifest 覆盖全部股票批次时，才设置 `historyComplete=true`。

- [ ] **步骤 4：运行测试确认通过**

运行同步骤 2；预期 PASS。

- [ ] **步骤 5：提交**

```bash
git add infra/risk-data-gateway/src/risk_gateway/datasets/breadth.py \
  infra/risk-data-gateway/tests/test_breadth.py
git commit -m "feat(risk): 计算可审计历史市场宽度"
```

### 任务 7：跨市场传导数据集

**文件：**
- 创建：`infra/risk-data-gateway/src/risk_gateway/datasets/cross_market.py`
- 测试：`infra/risk-data-gateway/tests/test_cross_market.py`

- [ ] **步骤 1：编写时区、相关性和确认数测试**

```python
def test_cross_market_uses_only_prior_available_global_closes():
    response = CrossMarketDataset(context()).fetch(query_for("market:CN-A"))
    row = response.data[-1]
    assert row["observedMarketCount"] == 4
    assert row["confirmedDownMarketCount"] == 3
    assert Decimal(str(row["dynamicCorrelation"])) == Decimal("0.6250000000")
```

- [ ] **步骤 2：运行测试确认失败**

运行：`docker build --target test -t stock-risk-data-gateway-test infra/risk-data-gateway && docker run --rm stock-risk-data-gateway-test pytest tests/test_cross_market.py -q`

预期：FAIL。

- [ ] **步骤 3：实现跨市场数据集**

固定免费资产篮子为标普 500、纳斯达克、恒生指数和日经 225；使用 `index_global_hist_em` 历史收盘，按任务 4 的可用时间映射至 A 股交易日。输出：等权领先资产日收益、目标与领先资产 60 日收益相关系数、当日负收益市场数和有效市场数。少于三个市场、相关窗口不足或时间无法对齐时返回历史不足。

- [ ] **步骤 4：运行测试确认通过**

运行同步骤 2；预期 PASS。

- [ ] **步骤 5：提交**

```bash
git add infra/risk-data-gateway/src/risk_gateway/datasets/cross_market.py \
  infra/risk-data-gateway/tests/test_cross_market.py
git commit -m "feat(risk): 对齐跨市场风险序列"
```

### 任务 8：行业有效期、ETF 缺失语义与 API 路由

**文件：**
- 创建：`infra/risk-data-gateway/src/risk_gateway/datasets/membership.py`
- 创建：`infra/risk-data-gateway/src/risk_gateway/datasets/etf.py`
- 修改：`infra/risk-data-gateway/src/risk_gateway/app.py`
- 测试：`infra/risk-data-gateway/tests/test_membership.py`
- 测试：`infra/risk-data-gateway/tests/test_etf.py`
- 测试：`infra/risk-data-gateway/tests/test_api.py`

- [ ] **步骤 1：编写不反填行业和不伪造 ETF 测试**

```python
def test_membership_never_backdates_current_snapshot():
    response = MembershipDataset(current_snapshot_only()).fetch(query_ending_before_fetch_date())
    assert response.data == []
    assert response.meta.insufficientHistory is True

def test_etf_endpoint_never_uses_nav_as_redemption():
    response = client.get("/api/risk/etf-redemption", params=valid_query()).json()
    assert response["data"] == []
    assert response["meta"]["historyComplete"] is False
    assert "redemption" in response["meta"]["historyGapReason"]
```

- [ ] **步骤 2：运行测试确认失败**

运行：`docker build --target test -t stock-risk-data-gateway-test infra/risk-data-gateway && docker run --rm stock-risk-data-gateway-test pytest tests/test_membership.py tests/test_etf.py tests/test_api.py -q`

预期：FAIL。

- [ ] **步骤 3：实现行业来源优先级**

先尝试 `stock_industry_clf_hist_sw()` 官方分类文件；只有记录同时包含股票、申万一级代码和生效日期时才生成区间，相邻生效记录推导上一分类的 `validTo=nextValidFrom-1`。该接口当前可能因申万官网 TLS/502 失败，失败时仅使用 `index_component_sw` 当前成分并把 `availableAt` 设为真实抓取时间，历史请求保持 `historyComplete=false`，禁止关闭 TLS 校验。

- [ ] **步骤 4：注册六个数据路由和统一异常映射**

路由将查询交给对应 Dataset，再统一分页。`InvalidQuery/InvalidCursor` 返回 400，AKTools 限频/超时返回 503，字段漂移和歧义修订返回 502，客观历史不足保持 200。ETF 固定返回无数据的历史不足响应，原因代码为 `free_source_has_no_historical_etf_redemption_fact`。

- [ ] **步骤 5：运行全部网关单元测试**

运行：`docker build --target test -t stock-risk-data-gateway-test infra/risk-data-gateway && docker run --rm stock-risk-data-gateway-test pytest -q`

预期：全部 PASS。

- [ ] **步骤 6：提交**

```bash
git add infra/risk-data-gateway
git commit -m "feat(risk): 完成衍生网关数据接口"
```

### 任务 9：Compose 与 Java 审计契约集成

**文件：**
- 修改：`docker-compose.market-data.yml`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/market/MarketDailyPoint.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/market/AkToolsMarketRiskSourceClient.java`
- 修改：`stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/market/MarketRiskDataProvider.java`
- 测试：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/data/market/AkToolsMarketRiskSourceClientTest.java`
- 测试：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/data/market/MarketRiskDataProviderTest.java`

- [ ] **步骤 1：编写 Java 审计字段失败测试**

```java
assertThat(point.benchmarkDefinition()).isEqualTo("CSI300");
assertThat(point.leaderDefinition()).isEqualTo("SSE50");
assertThat(point.proxy()).isTrue();
assertThat(observation.attributes())
        .containsEntry("benchmarkDefinition", "CSI300")
        .containsEntry("leaderDefinition", "SSE50")
        .containsEntry("proxy", true);
```

- [ ] **步骤 2：运行定向测试确认失败**

运行：

```bash
cd stock-ai-rule-system-service
'/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn' \
  -Dtest=AkToolsMarketRiskSourceClientTest,MarketRiskDataProviderTest test
```

预期：FAIL，`MarketDailyPoint` 尚无审计字段。

- [ ] **步骤 3：最小扩展 Java 内部契约**

为 `MarketDailyPoint` 增加 `benchmarkDefinition`、`leaderDefinition`、`proxy`；解析时要求网关响应提供前两项，`proxy` 缺失默认为 `false`。在 `dailyObservations` 的 raw attributes 中写入这些值，不修改任何评分计算。

- [ ] **步骤 4：扩展 Compose**

新增：

```yaml
  risk-data-gateway:
    build:
      context: ./infra/risk-data-gateway
    environment:
      RISK_GATEWAY_AKTOOLS_BASE_URL: http://aktools:8090
      RISK_GATEWAY_CACHE_DIR: /cache
    ports:
      - "127.0.0.1:18090:18090"
    volumes:
      - risk-data-gateway-cache:/cache
    depends_on:
      aktools:
        condition: service_healthy
    restart: unless-stopped

volumes:
  risk-data-gateway-cache:
```

- [ ] **步骤 5：运行 Java 定向测试和 Compose 校验**

运行：

```bash
docker compose -f docker-compose.market-data.yml config --quiet
cd stock-ai-rule-system-service
'/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn' \
  -Dtest=AkToolsMarketRiskSourceClientTest,MarketRiskDataProviderTest test
```

预期：两条命令退出码均为 0。

- [ ] **步骤 6：提交**

```bash
git add docker-compose.market-data.yml \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/market/MarketDailyPoint.java \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/market/AkToolsMarketRiskSourceClient.java \
  stock-ai-rule-system-service/src/main/java/com/jx/tracker/risk/data/market/MarketRiskDataProvider.java \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/data/market/AkToolsMarketRiskSourceClientTest.java \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/data/market/MarketRiskDataProviderTest.java
git commit -m "feat(risk): 接入衍生网关运行环境"
```

### 任务 10：真实契约冒烟与运维文档

**文件：**
- 修改：`stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/data/AkToolsContractSmokeTest.java`
- 创建：`infra/risk-data-gateway/scripts/smoke.sh`
- 修改：`doc/风险模块/A股风险市场数据Provider说明.md`
- 修改：`doc/风险模块/五年真实回填运行手册.md`

- [ ] **步骤 1：增加默认跳过的真实网关契约测试**

使用 `RISK_DERIVED_GATEWAY_IT=true` 开启，逐一验证五个市场端点返回 `data` 数组和完整 `meta`；ETF 验证 `historyComplete=false` 且空数组。日志只打印端点名、行数、最早日期和质量，不打印完整响应。

- [ ] **步骤 2：编写一键冒烟脚本**

`smoke.sh` 依次检查 `/health`、五个市场端点和 ETF 缺失语义。脚本使用 `set -eu`，请求固定 `market:CN-A` 和一个交易日小窗口，任何 schema 错误返回非零。

- [ ] **步骤 3：更新文档**

记录：启动 Compose、查看健康、缓存卷位置、重建缓存、运行网关测试、运行 Java 真实契约、行业官方文件 TLS/502 的处理原则、A2 不参与评分及代理定义。

- [ ] **步骤 4：运行静态和定向验证**

运行：

```bash
docker build --target test -t stock-risk-data-gateway-test infra/risk-data-gateway
docker run --rm stock-risk-data-gateway-test pytest -q
docker compose -f docker-compose.market-data.yml config --quiet
cd stock-ai-rule-system-service
'/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn' \
  -Dtest=AkToolsContractSmokeTest,AkToolsMarketRiskSourceClientTest,MarketRiskDataProviderTest test
```

预期：全部退出码为 0；未设置真实冒烟变量时真实网络用例显示 skipped。

- [ ] **步骤 5：提交**

```bash
git add infra/risk-data-gateway/scripts/smoke.sh \
  stock-ai-rule-system-service/src/test/java/com/jx/tracker/risk/data/AkToolsContractSmokeTest.java \
  doc/风险模块/A股风险市场数据Provider说明.md \
  doc/风险模块/五年真实回填运行手册.md
git commit -m "docs(risk): 完善衍生网关验收流程"
```

### 任务 11：容器真实启动与样本回填

**文件：**
- 运行产物：`stock-ai-rule-system-service/target/risk-backfill/reports/risk-backfill-*.json`
- 运行产物：Docker 卷 `risk-data-gateway-cache`

- [ ] **步骤 1：构建并启动两个数据服务**

运行：

```bash
docker compose -f docker-compose.market-data.yml up -d --build aktools risk-data-gateway
docker compose -f docker-compose.market-data.yml ps
```

预期：`stock-ai-rule-aktools` 和网关均为 healthy；端口仅绑定 `127.0.0.1`。

- [ ] **步骤 2：运行真实契约冒烟**

运行：

```bash
RISK_DERIVED_GATEWAY_IT=true \
RISK_AKTOOLS_IT=true \
RISK_AKTOOLS_BASE_URL=http://127.0.0.1:8090 \
RISK_WARNING_DERIVED_GATEWAY_BASE_URL=http://127.0.0.1:18090 \
'/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn' \
  -f stock-ai-rule-system-service/pom.xml -Dtest=AkToolsContractSmokeTest test
```

预期：契约测试通过；客观历史不足以质量响应表示，不出现 schema 或连接错误。

- [ ] **步骤 3：执行固定单只预热，再执行 50 只样本**

先设置现有运行手册中的数据库环境变量，再运行：

```bash
cd stock-ai-rule-system-service
export RISK_WARNING_BACKFILL_MODE=sample
export RISK_WARNING_BACKFILL_SYMBOLS=600519.SH
export RISK_WARNING_BACKFILL_END_DATE=2026-07-17
export RISK_WARNING_BACKFILL_CONFIRMATION=BACKFILL_5Y
export RISK_WARNING_AKTOOLS_BASE_URL=http://127.0.0.1:8090
export RISK_WARNING_DERIVED_GATEWAY_BASE_URL=http://127.0.0.1:18090
./scripts/risk-backfill.sh
```

单只成功后取消 `RISK_WARNING_BACKFILL_SYMBOLS` 并再次运行 `sample`。预期最终退出码为 0；若免费源客观导致覆盖不足，保留真实报告并停止，不修改门槛。

- [ ] **步骤 4：验证落库与幂等**

记录第一次观测、快照、证据和闸门行数；使用相同结束日重跑一次，确认唯一键数据量稳定、checkpoint 单调前进、`enforced=0` 且不存在 `available_at < observed_at`。

- [ ] **步骤 5：执行完整测试**

运行：

```bash
docker run --rm stock-risk-data-gateway-test pytest -q
cd stock-ai-rule-system-service
'/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn' test
```

预期：网关与 Maven 全部测试通过。

- [ ] **步骤 6：提交仅源码与文档**

运行产物、日志和缓存不提交。执行 `git status --short`，预期不包含数据库备份、JSON 运行报告、缓存 Parquet、日志或环境变量；任务 10 后没有源码修正时不创建空提交。
