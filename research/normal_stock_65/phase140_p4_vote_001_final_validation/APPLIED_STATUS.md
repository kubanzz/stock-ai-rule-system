# P4-VOTE-001 系统应用状态

状态日期：`2026-10-05`（北京时间）。**已应用（已写入配置），草稿待启用**。三个基本规则、规则组和方案已保存为 `draft/v1`；原方案 `RS_G144_G118_SZ125` 保持 `active/v2` 完整快照，本次保存不表示已运行P4规则。本文件为独立应用记录，不修改本目录已冻结的规则来源、`REGISTRATION.json`、最终窗口、历史诊断及原有文档字节。

已通过API回读核验的配置如下；完整写入及回读证据见[部署记录](../../deployments/p4_vote_001_20261005/DEPLOYMENT.md)与[VERIFICATION.json](../../deployments/p4_vote_001_20261005/VERIFICATION.json)。

| 对象 | 编码或名称 | ID / 状态 | 固定内容 |
| --- | --- | --- | --- |
| 基本规则 | `R_P4_VOTE_001_CHANGE_PCT_5D` | 26 / `draft/v1` | `change_pct_5d <= -0.10` |
| 基本规则 | `R_P4_VOTE_001_OPEN_GAP` | 27 / `draft/v1` | `open_gap >= 0.02` |
| 基本规则 | `R_P4_VOTE_001_INDEX_CLOSE_POSITION` | 28 / `draft/v1` | 沪深300 `index_close_position <= 0.20` |
| 规则组 | `P4-VOTE-001` | 5 / `draft/v1` | `WEIGHTED`，三个成员至少命中两个 |
| 股票组 | `P4-VOTE-001 冻结PIT100` | 4 / 已保存 | 原 `phase5/pit_assignment.csv` 的100只股票 |
| 应用方案 | `P4-VOTE-001` | 4 / `draft/v1` | 绑定上述规则组及冻结PIT100股票组 |

股票池编码为 `custom-2d568d16b60c49f5ab85fa403568f21e`，原名单SHA-256为 `fa6b9a06e3ca0f79ccf0a3757961a03e68cc508b7dab770be2ec87102f7c3950`。100只成员逐项与冻结名单一致，保留其中5只已退市身份及 `delisted` 状态，未因当前存续状态删股或递补。三个原子各记30分、方案看涨阈值60；分数用于三选二信号聚合，不是上涨概率。63项后端定向测试通过，包括原子计算、真实Drools逻辑、因子集成、范围、草稿保存及原方案行为验证；测试不能替代预测效果验证。

规则来源仍为 `phase4/bullish_group_results.json` 的 `results.vote[0]` 和 `phase5/pit_fixed_vote_stress.json` 的 `fixed_group`。正常股资格与固定三选二规则不因应用而新增、删除或调参。预测周期为T日收盘发信号、T+1市场开盘入场、T+2市场开盘退出；严格 `exit_open > entry_open` 才算上涨，持平、成熟缺价及停牌按原研究口径计错。

研究状态保持 **`pending_final`（待最终验证）**，最终验证窗口保持 **`2027-01-01..2029-12-31`**。系统写入或启用只表示规则配置已接入，不能替代36个月真实前瞻观察、成熟目标、支持量和总体命中率验收；当前没有“通过”结论。所有输出仅作为看涨辅助决策信号，不构成确定性预测、收益保证或自动交易授权。
