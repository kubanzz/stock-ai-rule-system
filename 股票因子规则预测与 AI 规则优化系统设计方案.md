# 股票因子规则预测与 AI 规则优化系统设计方案

## 1. 项目定位

本系统不是直接保证预测股票涨跌的“神奇预测器”，而是一个：

> **基于多源数据采集、多因子计算、规则引擎推理、AI 复盘分析、自动回测验证的股票信号辅助决策平台。**

核心目标是：

```
采集数据 → 计算因子 → 规则推理 → 输出信号 → 验证结果 → AI 复盘 → 生成候选规则 → 回测 → 人工审核 → 上线
```

系统输出的是：

```
看涨 / 看跌 / 观望 / 高风险
```

而不是简单输出：

```
明天一定涨 / 明天一定跌
```

------

# 2. 总体架构

## 2.1 整体架构图

```
┌──────────────────────────────────────────────┐
│                前端展示层                     │
│  股票列表 / 信号看板 / 规则管理 / 回测报告     │
└──────────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│                应用服务层                     │
│  股票服务 / 因子服务 / 规则服务 / AI复盘服务    │
│  回测服务 / 信号服务 / 任务调度 / 权限管理      │
└──────────────────────────────────────────────┘
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
┌────────────┐  ┌────────────┐  ┌────────────┐
│ 数据采集层 │  │ 规则引擎层 │  │ AI分析层   │
│ 行情/财报  │  │ Drools     │  │ LLM/模型   │
│ 新闻/宏观  │  │ 规则编排   │  │ 误判归因   │
└────────────┘  └────────────┘  └────────────┘
        │              │              │
        ▼              ▼              ▼
┌──────────────────────────────────────────────┐
│                数据存储层                     │
│ PostgreSQL / ClickHouse / Redis / 对象存储     │
│ 行情库 / 因子库 / 规则库 / 信号库 / 回测库      │
└──────────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│                任务调度层                     │
│ 定时采集 / 收盘复盘 / AI分析 / 自动回测         │
└──────────────────────────────────────────────┘
```

------

# 3. 系统核心流程

## 3.1 每日预测流程

```
1. 定时采集股票行情、财报、新闻、资金流、指数数据
2. 清洗数据并计算技术指标、基本面指标、情绪指标
3. 将原始指标转成标准化因子
4. 调用 Drools 规则引擎进行规则推理
5. 输出股票信号：看涨、看跌、观望、高风险
6. 保存信号、触发规则、评分、解释原因
7. 前端展示预测结果
```

## 3.2 收盘后验证流程

```
1. 收盘后采集实际涨跌幅
2. 对比系统预测结果
3. 计算预测是否命中
4. 统计每条规则的表现
5. 识别误判样本
6. 交给 AI 做误判归因
7. AI 生成规则优化建议
8. 候选规则进入回测流程
```

## 3.3 AI 规则优化流程

```
预测错误样本
    ↓
AI 分析错误原因
    ↓
生成候选规则 / 修改建议
    ↓
进入候选规则池
    ↓
历史回测
    ↓
模拟盘观察
    ↓
人工审核
    ↓
正式上线
```

注意：
**AI 不直接修改生产规则。**
它只生成候选规则，由回测和人工审核决定是否上线。

------

# 4. 模块设计

## 4.1 数据采集模块

### 4.1.1 模块职责

负责采集股票相关数据，包括：

```
行情数据
财报数据
新闻数据
资金流数据
指数数据
行业数据
宏观数据
```

### 4.1.2 子模块

| 子模块       | 说明                                  |
| ------------ | ------------------------------------- |
| 行情采集器   | 采集日 K、分钟 K、成交量、涨跌幅      |
| 财报采集器   | 采集营收、净利润、毛利率、ROE、PE、PB |
| 新闻采集器   | 采集新闻标题、正文、来源、发布时间    |
| 舆情采集器   | 采集社交媒体、研报摘要、公告          |
| 指数采集器   | 采集大盘指数、行业指数                |
| 资金流采集器 | 采集主力资金、北向资金、ETF 流入流出  |
| 宏观采集器   | 采集利率、CPI、汇率、政策事件等       |

### 4.1.3 数据采集频率

| 数据类型  | 建议频率           |
| --------- | ------------------ |
| 日 K 数据 | 每日收盘后         |
| 分钟行情  | 每 1 分钟 / 5 分钟 |
| 新闻数据  | 每 5 到 15 分钟    |
| 财报数据  | 每日更新           |
| 宏观数据  | 每日或事件触发     |
| 行业指数  | 每日或实时         |
| 资金流    | 每日或盘中         |

------

## 4.2 数据清洗与标准化模块

### 4.2.1 模块职责

把原始数据清洗成统一结构，去除异常值、重复数据和无效数据。

### 4.2.2 主要功能

```
去重
补全
格式标准化
交易日对齐
异常值过滤
缺失值处理
时间戳统一
股票代码映射
数据来源可信度标记
```

### 4.2.3 示例

原始行情数据：

```
{
  "symbol": "AAPL",
  "close": 195.3,
  "volume": 82000000,
  "change_pct": 2.15,
  "trade_date": "2026-06-20"
}
```

标准化后：

```
{
  "symbol": "AAPL",
  "market": "US",
  "trade_date": "2026-06-20",
  "close_price": 195.3,
  "volume": 82000000,
  "daily_return": 0.0215,
  "data_quality": "normal"
}
```

------

## 4.3 因子计算模块

### 4.3.1 模块职责

将原始数据转换成可被规则引擎使用的因子。

### 4.3.2 因子分类

| 因子类型   | 示例                           |
| ---------- | ------------------------------ |
| 技术因子   | MA、MACD、RSI、KDJ、布林带     |
| 趋势因子   | 5 日涨幅、20 日涨幅、均线排列  |
| 成交量因子 | 放量、缩量、量价背离           |
| 基本面因子 | 营收增长、净利润增长、ROE、PE  |
| 财报因子   | 是否超预期、业绩指引是否改善   |
| 舆情因子   | 新闻情绪、负面新闻数量         |
| 市场因子   | 大盘趋势、行业强弱             |
| 风险因子   | 高位过热、连续下跌、波动率异常 |

### 4.3.3 因子标准化示例

原始指标：

```
{
  "rsi": 78,
  "price_change_5d": 13.5,
  "volume_ratio": 2.4,
  "news_sentiment": -0.62,
  "market_trend": "weak"
}
```

转换成因子：

```
{
  "technical_status": "overbought",
  "short_term_trend": "strong_up",
  "volume_status": "abnormal_high",
  "sentiment_status": "negative",
  "market_status": "weak"
}
```

规则引擎更适合处理这种标准化后的语义因子。

------

## 4.4 规则引擎模块

建议使用 **Drools** 作为规则引擎，第一版也可以先用轻量 JSON Rule Engine，后续再迁移到 Drools。

### 4.4.1 模块职责

根据因子输入，执行规则推理，输出股票信号。

### 4.4.2 规则分类

| 规则类型     | 说明                             |
| ------------ | -------------------------------- |
| 趋势规则     | 判断是否处于上涨、下跌、震荡趋势 |
| 突破规则     | 判断是否突破均线、压力位         |
| 风险规则     | 判断是否过热、放量滞涨、跌破支撑 |
| 财报规则     | 判断财报是否改善或恶化           |
| 舆情规则     | 判断新闻情绪是否影响股价         |
| 市场环境规则 | 判断大盘、行业是否支持信号       |
| 买入信号规则 | 输出看涨或关注                   |
| 卖出信号规则 | 输出看跌或回避                   |
| 降级规则     | 在风险较高时降低信号等级         |
| 冲突处理规则 | 处理看涨和看跌同时出现的情况     |

### 4.4.3 规则推理输入

```
{
  "symbol": "AAPL",
  "trade_date": "2026-06-20",
  "factors": {
    "short_term_trend": "strong_up",
    "volume_status": "abnormal_high",
    "technical_status": "overbought",
    "sentiment_status": "negative",
    "market_status": "weak",
    "fundamental_status": "good"
  }
}
```

### 4.4.4 规则推理输出

```
{
  "symbol": "AAPL",
  "signal": "watch",
  "bullish_score": 55,
  "bearish_score": 38,
  "risk_score": 72,
  "confidence": 0.61,
  "triggered_rules": [
    "R_TREND_BREAKOUT_001",
    "R_RISK_OVERBOUGHT_002",
    "R_SENTIMENT_NEGATIVE_003"
  ],
  "reason": [
    "短期趋势较强",
    "成交量明显放大",
    "RSI 过热，短期存在回调风险",
    "新闻情绪偏负面"
  ]
}
```

### 4.4.5 规则示例

```
规则：强势突破看涨

如果：
  短期趋势 = strong_up
  且 成交量状态 = abnormal_high
  且 股价站上 MA20
  且 市场状态 != weak

那么：
  bullish_score += 25
  reason += "股价放量突破，短期趋势偏强"
规则：高位过热风险

如果：
  RSI > 75
  且 5 日涨幅 > 12%

那么：
  risk_score += 30
  bullish_score -= 10
  reason += "短期涨幅较大且 RSI 过热，存在回调风险"
规则：负面舆情降级

如果：
  sentiment_status = negative
  且 negative_news_count >= 3

那么：
  bearish_score += 20
  risk_score += 20
  signal 降一级
```

------

## 4.5 信号评分模块

### 4.5.1 模块职责

对规则引擎产生的分数进行归一化，得到最终信号。

### 4.5.2 核心评分

```
bullish_score：看涨分
bearish_score：看跌分
risk_score：风险分
confidence：置信度
```

### 4.5.3 信号判定逻辑

```
如果 bullish_score >= 70 且 risk_score < 50：
    强看涨

如果 bullish_score >= 55 且 risk_score < 70：
    偏看涨

如果 bearish_score >= 60：
    偏看跌

如果 risk_score >= 80：
    高风险回避

否则：
    观望
```

### 4.5.4 输出示例

```
{
  "symbol": "TSLA",
  "signal": "bullish",
  "signal_level": "偏看涨",
  "confidence": 0.66,
  "risk_level": "中高",
  "holding_period": "3-5个交易日",
  "explanation": "趋势突破和成交量放大支持看涨，但 RSI 偏高，需要注意短期回调。"
}
```

------

## 4.6 回测模块

这是整个系统最关键的模块之一。

### 4.6.1 模块职责

验证规则和策略在历史数据中的表现。

### 4.6.2 回测对象

```
单条规则
规则组合
完整策略
AI 候选规则
不同股票池
不同行业
不同市场环境
```

### 4.6.3 回测指标

| 指标       | 说明                         |
| ---------- | ---------------------------- |
| 触发次数   | 规则历史触发频率             |
| 胜率       | 触发后上涨或下跌判断正确比例 |
| 平均收益   | 触发后平均收益               |
| 最大回撤   | 历史最大亏损区间             |
| 盈亏比     | 平均盈利 / 平均亏损          |
| 夏普比率   | 风险调整后收益               |
| 交易次数   | 策略换手频率                 |
| 手续费影响 | 扣除成本后的收益             |
| 滑点影响   | 实际成交误差                 |
| 样本外表现 | 非训练区间表现               |

### 4.6.4 回测周期

```
1 日
3 日
5 日
10 日
20 日
60 日
```

### 4.6.5 回测结果示例

```
{
  "rule_id": "R_TREND_BREAKOUT_001",
  "period": "2023-01-01 ~ 2026-06-20",
  "trigger_count": 842,
  "win_rate_5d": 0.57,
  "avg_return_5d": 0.018,
  "max_drawdown": -0.092,
  "sharpe": 1.21,
  "conclusion": "规则在牛市和震荡市表现较好，在弱势市场下误判较多。"
}
```

------

## 4.7 AI 复盘分析模块

### 4.7.1 模块职责

AI 不直接参与交易，而是负责：

```
误判归因
规则表现分析
候选规则生成
规则修改建议
复盘报告生成
市场环境总结
```

### 4.7.2 输入数据

```
{
  "prediction": {
    "symbol": "AAPL",
    "date": "2026-06-19",
    "signal": "bullish",
    "triggered_rules": [
      "R_TREND_BREAKOUT_001",
      "R_VOLUME_UP_002"
    ]
  },
  "actual_result": {
    "next_day_return": -0.032,
    "next_5d_return": -0.047
  },
  "market_context": {
    "index_return": -0.025,
    "industry_return": -0.038,
    "news_sentiment": "negative"
  }
}
```

### 4.7.3 AI 输出内容

```
{
  "diagnosis": "本次误判主要原因是系统忽略了大盘和行业整体弱势，虽然个股出现放量突破，但属于弱势环境下的假突破。",
  "related_rules": [
    "R_TREND_BREAKOUT_001"
  ],
  "suggestions": [
    {
      "type": "add_filter",
      "rule_id": "R_TREND_BREAKOUT_001",
      "condition": "market_status != weak AND industry_status != weak",
      "reason": "避免在弱势市场中误判假突破",
      "risk": "可能减少部分强势个股机会",
      "need_backtest": true
    }
  ]
}
```

### 4.7.4 AI 提示词模板

```
你是一个量化策略分析助手。

请根据以下信息分析规则预测错误的原因：

1. 股票代码：
2. 预测日期：
3. 系统预测信号：
4. 实际涨跌表现：
5. 触发的规则：
6. 当时的技术指标：
7. 当时的市场环境：
8. 当时的新闻情绪：
9. 历史同类样本表现：

请输出：
- 误判原因
- 是否是规则缺失
- 是否是规则阈值不合理
- 是否受大盘/行业影响
- 是否建议修改规则
- 修改后的候选规则
- 是否需要回测
```

------

## 4.8 候选规则管理模块

### 4.8.1 模块职责

管理 AI 生成的规则建议。

### 4.8.2 规则状态机

```
draft        草稿
candidate    候选
backtesting  回测中
paper_trade  模拟盘观察
approved     已审核
active       已上线
disabled     已停用
archived     已归档
```

### 4.8.3 规则流转流程

```
AI 生成建议
    ↓
进入 candidate
    ↓
自动回测
    ↓
回测通过
    ↓
进入 paper_trade
    ↓
模拟盘观察
    ↓
人工审核
    ↓
正式 active
```

### 4.8.4 候选规则示例

```
{
  "candidate_rule_id": "CR_20260620_001",
  "source": "AI",
  "target_rule_id": "R_TREND_BREAKOUT_001",
  "change_type": "add_filter",
  "original_rule": "short_term_trend = strong_up AND volume_status = abnormal_high",
  "new_rule": "short_term_trend = strong_up AND volume_status = abnormal_high AND market_status != weak",
  "reason": "减少弱势市场下的假突破误判",
  "status": "candidate"
}
```

------

## 4.9 规则版本管理模块

### 4.9.1 模块职责

每次规则修改都必须有版本记录。

### 4.9.2 版本信息

```
规则 ID
规则名称
规则版本
规则内容
修改人
修改来源
修改原因
创建时间
上线时间
回测结果
审核状态
是否当前版本
```

### 4.9.3 示例

```
{
  "rule_id": "R_TREND_BREAKOUT_001",
  "version": "v1.3",
  "change_type": "add_filter",
  "change_reason": "AI 分析发现弱势市场下假突破较多",
  "approved_by": "admin",
  "status": "active",
  "created_at": "2026-06-20 18:30:00"
}
```

------

## 4.10 模拟盘模块

### 4.10.1 模块职责

候选规则不能直接进入生产，必须先进入模拟盘观察。

### 4.10.2 模拟盘内容

```
虚拟买入
虚拟卖出
收益统计
最大回撤统计
命中率统计
与生产规则对比
```

### 4.10.3 模拟盘价值

它可以回答一个问题：

```
这条新规则如果真实上线，最近一段时间是否比旧规则更好？
```

------

## 4.11 前端管理模块

### 4.11.1 页面设计

| 页面         | 功能                                |
| ------------ | ----------------------------------- |
| 股票信号看板 | 展示每日看涨、看跌、观望股票        |
| 股票详情页   | 展示指标、因子、触发规则、AI 解释   |
| 规则管理页   | 新增、编辑、启用、停用规则          |
| 候选规则页   | 查看 AI 生成的规则建议              |
| 回测报告页   | 查看规则历史表现                    |
| 复盘报告页   | 查看每日预测准确率和误判原因        |
| 模拟盘页面   | 查看候选规则模拟收益                |
| 数据源管理页 | 配置行情、新闻、财报数据源          |
| 系统任务页   | 查看采集任务、回测任务、AI 分析任务 |

### 4.11.2 股票信号看板字段

```
股票代码
股票名称
当前价格
涨跌幅
系统信号
看涨分
看跌分
风险分
置信度
触发规则数
建议周期
更新时间
```

------

# 5. 数据库设计

## 5.1 核心表

### 股票基础表：stock_base

```
CREATE TABLE stock_base (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL,
    name VARCHAR(128),
    market VARCHAR(32),
    industry VARCHAR(128),
    status VARCHAR(32),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
```

------

### 行情数据表：stock_daily_quote

```
CREATE TABLE stock_daily_quote (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL,
    trade_date DATE NOT NULL,
    open_price NUMERIC(18,4),
    high_price NUMERIC(18,4),
    low_price NUMERIC(18,4),
    close_price NUMERIC(18,4),
    volume NUMERIC(30,4),
    amount NUMERIC(30,4),
    change_pct NUMERIC(10,4),
    created_at TIMESTAMP
);
```

------

### 因子表：stock_factor_daily

```
CREATE TABLE stock_factor_daily (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL,
    trade_date DATE NOT NULL,
    factor_json JSONB NOT NULL,
    created_at TIMESTAMP
);
```

------

### 规则表：rule_definition

```
CREATE TABLE rule_definition (
    id BIGSERIAL PRIMARY KEY,
    rule_code VARCHAR(64) NOT NULL,
    rule_name VARCHAR(128),
    rule_type VARCHAR(64),
    rule_content TEXT,
    rule_format VARCHAR(32),
    version VARCHAR(32),
    status VARCHAR(32),
    priority INT,
    created_by VARCHAR(64),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
```

------

### 信号结果表：stock_signal_daily

```
CREATE TABLE stock_signal_daily (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL,
    signal_date DATE NOT NULL,
    signal VARCHAR(32),
    signal_level VARCHAR(32),
    bullish_score NUMERIC(10,4),
    bearish_score NUMERIC(10,4),
    risk_score NUMERIC(10,4),
    confidence NUMERIC(10,4),
    triggered_rules JSONB,
    explanation TEXT,
    created_at TIMESTAMP
);
```

------

### 实际表现表：stock_actual_result

```
CREATE TABLE stock_actual_result (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(32) NOT NULL,
    signal_date DATE NOT NULL,
    return_1d NUMERIC(10,4),
    return_3d NUMERIC(10,4),
    return_5d NUMERIC(10,4),
    return_10d NUMERIC(10,4),
    is_hit_1d BOOLEAN,
    is_hit_5d BOOLEAN,
    created_at TIMESTAMP
);
```

------

### AI 复盘表：ai_review_report

```
CREATE TABLE ai_review_report (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(32),
    review_date DATE,
    signal_id BIGINT,
    diagnosis TEXT,
    suggestions JSONB,
    model_name VARCHAR(64),
    created_at TIMESTAMP
);
```

------

### 候选规则表：candidate_rule

```
CREATE TABLE candidate_rule (
    id BIGSERIAL PRIMARY KEY,
    candidate_code VARCHAR(64),
    source VARCHAR(32),
    target_rule_code VARCHAR(64),
    change_type VARCHAR(64),
    original_content TEXT,
    proposed_content TEXT,
    reason TEXT,
    status VARCHAR(32),
    backtest_result JSONB,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
```

------

### 回测结果表：backtest_result

```
CREATE TABLE backtest_result (
    id BIGSERIAL PRIMARY KEY,
    object_type VARCHAR(32),
    object_code VARCHAR(64),
    start_date DATE,
    end_date DATE,
    trigger_count INT,
    win_rate NUMERIC(10,4),
    avg_return NUMERIC(10,4),
    max_drawdown NUMERIC(10,4),
    sharpe_ratio NUMERIC(10,4),
    result_json JSONB,
    created_at TIMESTAMP
);
```

------

# 6. 后端服务设计

## 6.1 服务划分

如果做单体应用，建议先做模块化单体。
如果后续做大，可以拆成微服务。

### MVP 阶段推荐

```
Spring Boot 单体服务
    ├── 数据采集模块
    ├── 因子计算模块
    ├── 规则引擎模块
    ├── 信号服务模块
    ├── 回测模块
    ├── AI 复盘模块
    ├── 规则管理模块
    └── 前端 API 模块
```

### 后续微服务版本

```
market-data-service        行情数据服务
factor-service             因子计算服务
rule-engine-service        规则引擎服务
signal-service             信号服务
backtest-service           回测服务
ai-review-service          AI 复盘服务
rule-admin-service         规则管理服务
user-auth-service          用户权限服务
```

------

# 7. API 设计

## 7.1 股票信号 API

### 查询某日信号

```
GET /api/signals?date=2026-06-20&signal=bullish
```

返回：

```
{
  "date": "2026-06-20",
  "items": [
    {
      "symbol": "AAPL",
      "signal": "bullish",
      "bullish_score": 72,
      "risk_score": 45,
      "confidence": 0.68
    }
  ]
}
```

------

## 7.2 股票详情 API

```
GET /api/stocks/{symbol}/analysis?date=2026-06-20
```

返回：

```
{
  "symbol": "AAPL",
  "signal": "bullish",
  "factors": {},
  "triggered_rules": [],
  "explanation": "趋势突破，成交量放大，基本面良好。"
}
```

------

## 7.3 规则管理 API

### 新增规则

```
POST /api/rules
```

### 修改规则

```
PUT /api/rules/{ruleCode}
```

### 启用规则

```
POST /api/rules/{ruleCode}/enable
```

### 停用规则

```
POST /api/rules/{ruleCode}/disable
```

------

## 7.4 回测 API

```
POST /api/backtests
```

请求：

```
{
  "object_type": "rule",
  "object_code": "R_TREND_BREAKOUT_001",
  "start_date": "2023-01-01",
  "end_date": "2026-06-20",
  "holding_period": 5
}
```

------

## 7.5 AI 复盘 API

```
POST /api/ai/review
```

请求：

```
{
  "date": "2026-06-20",
  "mode": "daily"
}
```

------

# 8. 技术选型建议

## 8.1 后端

| 模块     | 技术                               |
| -------- | ---------------------------------- |
| JDK      | JDK 21 LTS                         |
| 主后端   | Spring Boot 3.5.15                 |
| 规则引擎 | Drools                             |
| 定时任务 | XXL-JOB / Quartz                   |
| 消息队列 | Kafka / RabbitMQ                   |
| ORM      | MyBatis Plus / JPA                 |
| API 文档 | OpenAPI / Swagger（SpringDoc 2.x） |

## 8.2 数据存储

| 数据         | 技术                     |
| ------------ | ------------------------ |
| 业务库       | PostgreSQL               |
| 行情时序数据 | ClickHouse / TimescaleDB |
| 缓存         | Redis                    |
| 日志         | Elasticsearch / Loki     |
| 文件报告     | MinIO / S3               |

## 8.3 AI 模块

| 功能         | 技术                         |
| ------------ | ---------------------------- |
| 复盘分析     | LLM                          |
| 新闻情绪分析 | LLM / NLP 分类模型           |
| 候选规则生成 | LLM                          |
| 规则评估     | Python + Pandas              |
| 机器学习扩展 | LightGBM / XGBoost / PyTorch |

## 8.4 前端

| 模块     | 技术                      |
| -------- | ------------------------- |
| Web 前端 | Vue 3 / React             |
| UI 框架  | Element Plus / Ant Design |
| 图表     | ECharts                   |
| 状态管理 | Pinia / Redux             |
| 表格     | AG Grid / VxeTable        |

------

# 9. Drools 规则设计建议

## 9.1 Fact 对象设计

```
public class StockFactorFact {
    private String symbol;
    private LocalDate tradeDate;

    private BigDecimal rsi;
    private BigDecimal macd;
    private BigDecimal priceChange5d;
    private BigDecimal volumeRatio;

    private String shortTermTrend;
    private String volumeStatus;
    private String sentimentStatus;
    private String marketStatus;
    private String industryStatus;
    private String fundamentalStatus;

    private BigDecimal bullishScore = BigDecimal.ZERO;
    private BigDecimal bearishScore = BigDecimal.ZERO;
    private BigDecimal riskScore = BigDecimal.ZERO;

    private List<String> triggeredRules = new ArrayList<>();
    private List<String> reasons = new ArrayList<>();
}
```

## 9.2 Drools 规则示例

```
rule "R_TREND_BREAKOUT_001"
salience 100
when
    $f : StockFactorFact(
        shortTermTrend == "strong_up",
        volumeStatus == "abnormal_high",
        marketStatus != "weak"
    )
then
    $f.addBullishScore(new BigDecimal("25"));
    $f.addTriggeredRule("R_TREND_BREAKOUT_001");
    $f.addReason("短期趋势强，成交量放大，市场环境未明显走弱");
end
rule "R_RISK_OVERBOUGHT_001"
salience 90
when
    $f : StockFactorFact(
        rsi.compareTo(new BigDecimal("75")) > 0,
        priceChange5d.compareTo(new BigDecimal("12")) > 0
    )
then
    $f.addRiskScore(new BigDecimal("30"));
    $f.addBullishScore(new BigDecimal("-10"));
    $f.addTriggeredRule("R_RISK_OVERBOUGHT_001");
    $f.addReason("RSI 过热且短期涨幅较大，存在回调风险");
end
```

------

# 10. AI 自动优化的安全设计

## 10.1 禁止 AI 直接做的事

```
禁止直接修改生产规则
禁止直接生成真实交易指令
禁止绕过回测上线规则
禁止根据单日误判立即调整规则
禁止删除已有规则版本
```

## 10.2 允许 AI 自动做的事

```
分析误判原因
生成复盘报告
提出规则修改建议
生成候选规则
给规则打风险标签
发现可能失效的规则
调用回测任务
```

## 10.3 AI 输出必须结构化

AI 不能只输出自然语言，必须输出 JSON：

```
{
  "action": "propose_rule_change",
  "target_rule": "R_TREND_BREAKOUT_001",
  "change_type": "add_filter",
  "new_condition": "market_status != weak",
  "reason": "弱势市场下假突破较多",
  "risk": "可能减少部分真实突破机会",
  "need_backtest": true
}
```

------

# 11. 权限与审计设计

## 11.1 角色设计

| 角色         | 权限                    |
| ------------ | ----------------------- |
| 管理员       | 全部权限                |
| 策略研究员   | 新增候选规则、查看回测  |
| 审核员       | 审核规则上线            |
| 观察员       | 只读                    |
| 系统任务账号 | 执行采集、回测、AI 任务 |

## 11.2 审计日志

必须记录：

```
谁修改了规则
什么时候修改
修改前内容
修改后内容
修改原因
是否经过回测
是否经过审核
上线时间
回滚记录
```

------

# 12. MVP 开发范围建议

第一版不要做太大，建议做这个范围：

## 12.1 MVP 核心功能

```
股票基础数据管理
日 K 行情采集
技术指标计算
规则引擎推理
每日信号输出
预测结果验证
单规则回测
AI 误判复盘
候选规则生成
人工审核上线
前端信号看板
```

## 12.2 MVP 暂不做

```
自动真实交易
高频交易
分钟级复杂策略
复杂组合优化
多账户管理
高级机器学习模型
全市场实时扫描
```

------

# 13. 迭代路线

## 第一阶段：规则预测 MVP

目标：跑通数据 → 因子 → 规则 → 信号。

```
1. 股票基础库
2. 日 K 数据采集
3. MA / RSI / MACD / 成交量因子
4. 规则配置
5. Drools 推理
6. 信号看板
```

## 第二阶段：回测系统

目标：验证规则是否有效。

```
1. 单规则回测
2. 组合规则回测
3. 胜率统计
4. 收益统计
5. 最大回撤统计
6. 回测报告页面
```

## 第三阶段：AI 复盘

目标：让 AI 分析预测错误原因。

```
1. 误判样本收集
2. AI 归因分析
3. 每日复盘报告
4. 候选规则生成
5. 候选规则管理
```

## 第四阶段：AI 规则优化闭环

目标：AI 提建议，系统自动验证。

```
1. AI 生成候选规则
2. 自动回测
3. 模拟盘观察
4. 人工审核
5. 规则版本管理
6. 灰度上线
```

## 第五阶段：策略增强

目标：提高系统实用性。

```
1. 新闻情绪分析
2. 财报事件分析
3. 行业轮动分析
4. 资金流因子
5. 机器学习评分模型
6. 多策略组合
```

------

# 14. 推荐目录结构

如果用 Spring Boot，可以这样组织：

```
stock-ai-rule-system
├── stock-api
│   ├── controller
│   ├── dto
│   └── vo
├── stock-core
│   ├── factor
│   ├── signal
│   ├── rule
│   └── backtest
├── stock-data
│   ├── collector
│   ├── cleaner
│   └── repository
├── stock-rule-engine
│   ├── drools
│   ├── fact
│   ├── ruleloader
│   └── executor
├── stock-ai
│   ├── review
│   ├── prompt
│   ├── llm
│   └── candidate
├── stock-scheduler
│   ├── jobs
│   └── tasks
├── stock-admin
│   ├── ruleadmin
│   ├── user
│   └── audit
└── stock-web
    └── frontend
```

------

# 15. 核心任务调度设计

## 15.1 每日任务

```
09:00    更新股票基础信息
09:15    盘前采集新闻和宏观数据
09:25    盘前计算市场状态
15:30    收盘行情采集
16:00    日线因子计算
16:30    规则引擎推理
17:00    生成每日信号
18:00    验证历史预测结果
19:00    AI 复盘分析
20:00    候选规则回测
21:00    生成日报
```

具体时间要根据 A 股、美股或其他市场调整。

------

# 16. 关键风险与控制措施

## 16.1 过拟合风险

控制方式：

```
规则不能过于复杂
必须有样本外测试
必须跨时间周期验证
必须跨行业验证
不能只看单次回测收益
```

## 16.2 数据泄露风险

控制方式：

```
预测时只能使用当时已经可见的数据
不能使用未来财报
不能使用收盘后新闻预测盘中走势
回测必须严格按时间切片
```

## 16.3 AI 幻觉风险

控制方式：

```
AI 输出必须结构化
AI 结论必须关联真实数据
AI 建议必须回测
AI 不能直接上线规则
重要结论要可追溯
```

## 16.4 规则冲突风险

控制方式：

```
规则优先级
规则分组
冲突解决规则
信号合并策略
风险规则优先级高于看涨规则
```

## 16.5 自动交易风险

第一版建议完全不接自动交易。

系统定位为：

```
研究工具
信号辅助工具
规则优化平台
```

而不是：

```
自动买卖系统
```

------

# 17. 最终推荐版本

我建议你的系统第一版目标定义为：

> **基于 Drools 的多因子股票信号推理系统 + AI 复盘与候选规则优化平台。**

核心能力：

```
1. 能采集行情和新闻数据
2. 能计算技术因子
3. 能把因子输入 Drools
4. 能输出看涨、看跌、观望、高风险信号
5. 能记录触发了哪些规则
6. 能验证预测是否正确
7. 能让 AI 分析预测错误原因
8. 能生成候选规则
9. 能自动回测候选规则
10. 能人工审核后上线规则
```

最重要的设计原则是：

```
AI 负责分析和建议
规则引擎负责确定性推理
回测系统负责验证
人负责最终审核
```

这样设计，系统既有 AI 的灵活性，又有规则引擎的可解释性和工程上的可控性。

------

# 18. 任务模块拆分与状态记录

| 序号 | 任务模块 | 状态 |
|---|---|---|
| 1 | 项目基础架构 | 未完成 |
| 2 | 行情与新闻数据采集 | 未完成 |
| 3 | 技术因子计算 | 未完成 |
| 4 | Drools 规则推理 | 未完成 |
| 5 | 信号输出与规则触发记录 | 未完成 |
| 6 | 预测结果验证 | 未完成 |
| 7 | AI 复盘分析 | 未完成 |
| 8 | 候选规则生成 | 未完成 |
| 9 | 候选规则回测 | 未完成 |
| 10 | 人工审核与规则上线 | 未完成 |