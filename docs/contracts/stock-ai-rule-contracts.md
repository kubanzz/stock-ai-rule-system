# 股票因子规则预测系统基础契约

## 定位

系统输出仅作为股票研究和辅助决策信号，不构成投资建议，不代表确定性预测，也不保证收益。后续所有信号、AI 解释和前端页面都必须展示同等含义的风险提示。

## 数据库

MVP 使用 MySQL。设计文档中的 PostgreSQL 类型已转换：

- `BIGSERIAL` -> `BIGINT PRIMARY KEY AUTO_INCREMENT`
- `JSONB` -> `JSON`
- `TIMESTAMP` -> `DATETIME`

DDL 文件位于 `stock-ai-rule-system-service/src/main/resources/db/stock_ai_rule_schema.sql`。

## 核心枚举

- 信号：`bullish`、`bearish`、`watch`、`high_risk`
- 规则状态：`draft`、`candidate`、`backtesting`、`paper_trade`、`approved`、`active`、`disabled`、`archived`
- 规则格式：`json`、`drools`
- 回测对象：`rule`、`rule_group`、`strategy`、`candidate_rule`

## API 契约

后端继续沿用当前 `AjaxResult` / `PageResult`：

- 成功响应码为 `200`
- 分页响应字段为 `total`、`rows`、`code`、`msg`
- 前端股票业务 API 需要单独兼容 `code=200`

MVP API 路径保持设计方案中的约定：

- `GET /api/signals`
- `GET /api/stocks/{symbol}/analysis`
- `POST /api/rules`
- `PUT /api/rules/{ruleCode}`
- `POST /api/rules/{ruleCode}/enable`
- `POST /api/rules/{ruleCode}/disable`
- `POST /api/backtests`
- `POST /api/ai/review`

## 配置

数据库地址、账号、密码、Druid 控制台账号密码必须通过环境变量或本地未提交配置注入，不要提交真实密钥、账号密码或 Token。
