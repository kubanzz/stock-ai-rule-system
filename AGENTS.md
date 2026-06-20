# AGENTS.md

## 项目概述

本项目为“股票因子规则预测与 AI 规则优化系统”，目标是构建基于多源数据采集、多因子计算、规则推理、AI 复盘分析与回测验证的股票信号辅助决策平台。

系统输出应定位为辅助决策信号，例如：看涨、看跌、观望、高风险；不得描述为保证收益或确定性预测。

## 目录结构

- `股票因子规则预测与 AI 规则优化系统设计方案.md`：系统设计方案与业务蓝图。
- `stock-ai-rule-system-service/`：后端服务，基于 JDK 21 LTS、Spring Boot 3.5.15、MyBatis-Plus、MySQL 等技术栈。
- `stock-ai-rule-system-ui/`：前端工程，基于 Vue 3、Vben Admin、pnpm、Turbo 的 monorepo 项目。

## 通用开发规则

- 变更前优先阅读相关设计文档、现有代码与配置，保持实现与当前架构一致。
- 避免无必要的大规模重构，优先进行小范围、可验证、可回滚的修改。
- 新增功能应遵循“数据采集 → 因子计算 → 规则推理 → 信号输出 → 回测验证 → AI 复盘”的业务主线。
- 涉及股票预测、规则解释、AI 分析时，必须保留风险提示与辅助决策定位，不得输出绝对化投资结论。
- 不提交密钥、账号、Token、数据库密码、真实个人信息等敏感内容。

## 后端规则

- 后端目录：`stock-ai-rule-system-service/`。
- Java 版本：`21`（JDK 21 LTS）。
- Spring Boot 版本：`3.5.15`。
- 包名与模块组织应保持在 `com.jx` 体系下。
- 数据访问优先沿用 MyBatis-Plus、现有实体、Mapper、Service、Controller 分层方式。
- Spring Boot 3 相关代码应使用 `jakarta.*` 命名空间，避免新增 `javax.servlet`、`javax.annotation` 等旧 API 引用。
- API 设计应保持 REST 风格，返回结构、分页方式、异常处理应与现有代码保持一致。
- 新增配置优先放在 `src/main/resources` 下的对应 `application*.yml` 中，不要硬编码环境相关参数。
- 后端常用命令（需确保 `JAVA_HOME` 指向 JDK 21）：
  - `cd stock-ai-rule-system-service && mvn test`
  - `cd stock-ai-rule-system-service && mvn spring-boot:run`

## 前端规则

- 前端目录：`stock-ai-rule-system-ui/`。
- 包管理器：`pnpm`，不要混用 `npm` 或 `yarn`。
- Node 版本要求：`^22.18.0 || ^24.0.0`。
- pnpm 版本要求：`>=11.0.0`，当前声明为 `pnpm@11.7.0`。
- 前端为 monorepo 结构，应用位于 `apps/`，共享能力位于 `packages/`、`internal/`。
- Vue、TypeScript、样式与组件写法应遵循 Vben Admin 现有规范。
- 新增页面、路由、接口请求、状态管理时，优先参考同目录已有实现。
- 前端常用命令：
  - `cd stock-ai-rule-system-ui && pnpm install`
  - `cd stock-ai-rule-system-ui && pnpm dev`
  - `cd stock-ai-rule-system-ui && pnpm lint`
  - `cd stock-ai-rule-system-ui && pnpm check`

## 文档规则

- 业务设计、架构说明、流程说明优先维护在根目录设计方案文档中。
- `AGENTS.md` 用于记录面向开发助手与协作者的项目工作规则，保持简洁、可执行。
- 文档统一使用简体中文，涉及命令、目录、类名、函数名时使用反引号标注。

## 验证规则

- 修改后端代码后，优先运行相关 Maven 测试或至少进行编译验证。
- 修改前端代码后，优先运行相关 lint、typecheck 或单元测试。
- 如果无法运行验证命令，应在交付说明中明确原因与未验证范围。
