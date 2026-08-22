# 股票 AI 规则系统技术架构推文实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:executing-plans 在当前会话中逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 生成一篇面向开发者与 AI 工程师、采用石墨极简风、可直接复制到微信公众号编辑器的项目技术架构文章。

**架构：** 先从项目设计方案与契约中整理一篇 Markdown 原稿，再按照 `gzh-design` 的石墨极简主题组件库映射为纯 `<section>` HTML。最后运行确定性校验并包装为带一键复制按钮的浏览器预览页。

**技术栈：** Markdown、公众号内联 HTML、`gzh-design` 石墨极简组件库、Python 校验与预览包装脚本。

---

## 文件结构

- 创建：`股票AI规则系统技术架构拆解.md`——文章可编辑原稿。
- 创建：`股票AI规则系统技术架构拆解_排版_石墨极简风(graphite-minimal).html`——可直接粘贴公众号的干净正文。
- 生成：`股票AI规则系统技术架构拆解_排版_石墨极简风(graphite-minimal)_预览.html`——带“复制到公众号”按钮的预览页。

### 任务 1：撰写项目技术架构原稿

**文件：**
- 参考：`doc/股票因子规则预测与 AI 规则优化系统设计方案.md`
- 参考：`docs/contracts/stock-ai-rule-contracts.md`
- 创建：`股票AI规则系统技术架构拆解.md`

- [ ] **步骤 1：按“一条信号的完整旅程”撰写 Markdown**

文章标题使用“我们没有让 AI 直接预测涨跌，而是造了一套会复盘的规则系统”，包含开头引用、六个二级章节、语义因子示例、结构化信号示例、反馈闭环和结尾风险提示。

- [ ] **步骤 2：检查业务边界**

运行：

```bash
rg -n '一定涨|一定跌|稳赚|保证收益|百分之百|100%' 股票AI规则系统技术架构拆解.md
```

预期：无匹配；文章明确说明输出仅为辅助决策信号，不构成投资建议。

### 任务 2：读取并映射石墨极简组件

**文件：**
- 参考：`.agents/skills/gzh-design/references/theme-graphite-minimal.md`
- 参考：`.agents/skills/gzh-design/references/common-components.md`
- 创建：`股票AI规则系统技术架构拆解_排版_石墨极简风(graphite-minimal).html`

- [ ] **步骤 1：读取两份组件库的完整内容**

确认设计变量、观点／深度分析配方、完整文章骨架、Markdown 映射、代码块与行内代码组件。

- [ ] **步骤 2：将 Markdown 结构映射为公众号 HTML**

使用石墨极简全局容器、开头引言卡、三项导读、超大水印章节标题、正文段落、浅灰引用块、紧凑代码块、流程列表、结尾分割线与唯一签名区。每个正文段落标记一至三个关键词，正文文字节点全部包裹 `<span leaf="">`。

- [ ] **步骤 3：检查产物外壳与禁用元素**

运行：

```bash
head -n 1 '股票AI规则系统技术架构拆解_排版_石墨极简风(graphite-minimal).html'
rg -n '<!DOCTYPE|<html|<head|<body|<style|<script|<div|class=|id=' '股票AI规则系统技术架构拆解_排版_石墨极简风(graphite-minimal).html'
```

预期：首行为 `<section`；禁用元素无匹配。

### 任务 3：校验并生成预览页

**文件：**
- 校验：`股票AI规则系统技术架构拆解_排版_石墨极简风(graphite-minimal).html`
- 生成：`股票AI规则系统技术架构拆解_排版_石墨极简风(graphite-minimal)_预览.html`

- [ ] **步骤 1：运行公众号 HTML 校验**

运行：

```bash
.agents/skills/gzh-design/scripts/validate_gzh_html.py '股票AI规则系统技术架构拆解_排版_石墨极简风(graphite-minimal).html'
```

预期：零 ERROR、零 WARNING；如有问题，修复 HTML 后重新运行完整命令。

- [ ] **步骤 2：生成一键复制预览页**

运行：

```bash
.agents/skills/gzh-design/scripts/wrap_preview.py '股票AI规则系统技术架构拆解_排版_石墨极简风(graphite-minimal).html'
```

预期：生成对应 `_预览.html`，并保留干净正文中的唯一全局 `<section>`。

- [ ] **步骤 3：执行最终验收**

运行：

```bash
test -s 股票AI规则系统技术架构拆解.md && \
test -s '股票AI规则系统技术架构拆解_排版_石墨极简风(graphite-minimal).html' && \
test -s '股票AI规则系统技术架构拆解_排版_石墨极简风(graphite-minimal)_预览.html'
```

预期：退出码为零，三个产物均存在且非空。
