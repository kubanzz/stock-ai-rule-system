# 风险观测冷热分层运维手册

## 适用范围

本文用于将 `risk_indicator_observation` 调整为最近两年热数据，并把更早完整记录迁入 `risk_indicator_observation_archive`。风险中心页面已经只读取评分快照和评分证据，因此该操作主要影响日常评分、历史回填与审计，不应改变页面接口契约。

以下操作必须先在测试环境验证。生产执行前应完成数据库备份，并确认 Flyway V4 已成功创建 `risk_indicator_baseline` 和 `risk_indicator_observation_archive`。

## 发布阶段

### 阶段一：只部署代码和表结构

保持以下默认配置：

```bash
RISK_STORAGE_TIERED_READ_ENABLED=false
RISK_STORAGE_ARCHIVE_ENABLED=false
```

此时：

- 风险中心已经只读取 `risk_score_snapshot` 和 `risk_score_evidence`；
- 新观测开始同步维护紧凑基线；
- 评分仍读取原始热表；
- 不会自动搬迁或删除历史数据。

### 阶段二：分批初始化紧凑基线

按月或按季度设置窗口，避免一次处理全部历史。下面 SQL 只展示单个窗口；重复执行时修改日期并在每个窗口后提交。

```sql
SET @window_start = '2015-01-01';
SET @window_end = '2015-04-01';

INSERT INTO risk_indicator_baseline (
    object_type, object_id, horizon, trade_date, dimension_code,
    indicator_code, component_code, actual_value, unit, observed_at,
    available_at, source, quality_status, already_normalized_risk_score,
    normalization_contract, dataset_code, trading_day, market_price
)
SELECT object_type, object_id, horizon, trade_date, dimension_code,
       indicator_code, component_code, actual_value, unit, observed_at,
       available_at, source, quality_status, already_normalized_risk_score,
       normalization_contract, dataset_code, trading_day, market_price
FROM (
    SELECT observation.object_type, observation.object_id, observation.horizon,
           observation.trade_date, observation.dimension_code,
           observation.indicator_code, observation.component_code,
           COALESCE(
               observation.indicator_value,
               CASE
                   WHEN JSON_TYPE(JSON_EXTRACT(
                       observation.payload_json, '$.auditValue'
                   )) IN ('INTEGER', 'DOUBLE')
                   THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(
                       observation.payload_json, '$.auditValue'
                   )) AS DECIMAL(30, 10))
               END
           ) AS actual_value,
           observation.unit, observation.observed_at, observation.available_at,
           observation.source, observation.quality_status,
           CASE JSON_UNQUOTE(JSON_EXTRACT(
               observation.payload_json, '$.alreadyNormalizedRiskScore'
           )) WHEN 'true' THEN 1 WHEN 'false' THEN 0 END
               AS already_normalized_risk_score,
           JSON_UNQUOTE(JSON_EXTRACT(
               observation.payload_json, '$.normalizationContract'
           )) AS normalization_contract,
           JSON_UNQUOTE(JSON_EXTRACT(
               observation.payload_json, '$.datasetCode'
           )) AS dataset_code,
           CASE JSON_UNQUOTE(JSON_EXTRACT(
               observation.payload_json, '$.tradingDay'
           )) WHEN 'true' THEN 1 WHEN 'false' THEN 0 END AS trading_day,
           CASE JSON_UNQUOTE(JSON_EXTRACT(
               observation.payload_json, '$.marketPrice'
           )) WHEN 'true' THEN 1 WHEN 'false' THEN 0 END AS market_price,
           ROW_NUMBER() OVER (
               PARTITION BY observation.object_type, observation.object_id,
                   observation.horizon, observation.trade_date,
                   observation.indicator_code, observation.component_code
               ORDER BY CASE WHEN observation.quality_status
                                  IN ('available', 'valid_zero')
                             THEN 1 ELSE 0 END DESC,
                        observation.available_at DESC,
                        observation.id DESC
           ) AS baseline_rank
    FROM risk_indicator_observation observation
    WHERE observation.trade_date >= @window_start
      AND observation.trade_date < @window_end
) ranked
WHERE baseline_rank = 1
ON DUPLICATE KEY UPDATE
    dimension_code = VALUES(dimension_code),
    actual_value = VALUES(actual_value),
    unit = VALUES(unit),
    observed_at = VALUES(observed_at),
    available_at = VALUES(available_at),
    source = VALUES(source),
    quality_status = VALUES(quality_status),
    already_normalized_risk_score = VALUES(already_normalized_risk_score),
    normalization_contract = VALUES(normalization_contract),
    dataset_code = VALUES(dataset_code),
    trading_day = VALUES(trading_day),
    market_price = VALUES(market_price);
```

每个窗口执行后检查：

```sql
SELECT MIN(trade_date) AS baseline_min_date,
       MAX(trade_date) AS baseline_max_date,
       COUNT(*) AS baseline_rows
FROM risk_indicator_baseline;

SELECT COUNT(*) AS missing_baseline_rows
FROM (
    SELECT DISTINCT object_type, object_id, horizon, trade_date,
           indicator_code, component_code
    FROM risk_indicator_observation
) observation
LEFT JOIN risk_indicator_baseline baseline
  ON baseline.object_type = observation.object_type
 AND baseline.object_id = observation.object_id
 AND baseline.horizon = observation.horizon
 AND baseline.trade_date = observation.trade_date
 AND baseline.indicator_code = observation.indicator_code
 AND baseline.component_code = observation.component_code
WHERE baseline.id IS NULL;
```

`missing_baseline_rows` 必须为 0。再抽样比较市场、行业和股票三个对象、三个周期的最新评分输入与评分结果。

### 阶段三：启用分层读取

确认基线完整后设置：

```bash
RISK_STORAGE_TIERED_READ_ENABLED=true
RISK_STORAGE_ARCHIVE_ENABLED=false
```

重启服务并执行一次市场同步和样本股票同步，比较切换前后的快照分数、完整度、证据数量和评分耗时。此阶段仍不删除热表数据。

### 阶段四：小批次启用冷归档

确认评分一致后设置：

```bash
RISK_STORAGE_TIERED_READ_ENABLED=true
RISK_STORAGE_ARCHIVE_ENABLED=true
RISK_STORAGE_HOT_RETENTION_YEARS=2
RISK_STORAGE_ARCHIVE_BATCH_SIZE=5000
RISK_STORAGE_MAX_BATCHES_PER_RUN=10
RISK_STORAGE_ARCHIVE_CRON="0 30 2 * * *"
```

每轮最多处理 5 万行。归档任务会在同一事务中：

1. 为本批缺失的自然键补写紧凑基线；
2. 校验基线覆盖；
3. 复制完整记录到冷表并按 ID 核对；
4. 删除热表对应 ID。

观察以下指标：

```sql
SELECT COUNT(*) AS hot_rows,
       MIN(trade_date) AS hot_min_date,
       MAX(trade_date) AS hot_max_date
FROM risk_indicator_observation;

SELECT COUNT(*) AS cold_rows,
       MIN(trade_date) AS cold_min_date,
       MAX(trade_date) AS cold_max_date
FROM risk_indicator_observation_archive;
```

首次运行建议把 `RISK_STORAGE_MAX_BATCHES_PER_RUN` 设为 1，确认事务时间、复制校验和历史回填读取正常后再逐步提高。

## 回滚

先关闭归档：

```bash
RISK_STORAGE_ARCHIVE_ENABLED=false
```

如果评分分层读取异常，再关闭：

```bash
RISK_STORAGE_TIERED_READ_ENABLED=false
```

关闭分层读取前，如热表已经发生归档，需要先恢复冷数据：

```sql
INSERT INTO risk_indicator_observation (
    id, object_type, object_id, horizon, trade_date, dimension_code,
    indicator_code, component_code, indicator_value, unit, observed_at,
    available_at, source, quality_status, payload_json, created_at
)
SELECT id, object_type, object_id, horizon, trade_date, dimension_code,
       indicator_code, component_code, indicator_value, unit, observed_at,
       available_at, source, quality_status, payload_json, created_at
FROM risk_indicator_observation_archive
ON DUPLICATE KEY UPDATE id = VALUES(id);
```

恢复并核对后再重启服务。冷表暂不删除，作为可恢复副本保留。

