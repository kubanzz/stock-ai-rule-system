# P4-VOTE-001 验证结果

运行：

```bash
python research/normal_stock_65/phase140_p4_vote_001_final_validation/generate_report.py
```

输出 `RESULT.json`、`HISTORICAL_TRIGGER_RECORDS.csv`、`FINAL_TRIGGER_RECORDS.csv` 和 `ARTIFACT_SHA256.json`。脚本先核验基础版本与冻结版本的规则成员及三选二逻辑，再复算 phase5/phase6 历史诊断；最终验证没有真实观察时只生成空统计，不制造命中率。窗口结束后，`final_evaluator.py` 按冻结登记计算“通过”“未通过”或“支持不足”，不会选择规则或参数。

前瞻数据须通过 `forward_ledger.py` 的双阶段接口追加：T 日收盘使用 `append_signal_commit` 写入全量 100 只股票的规则决定（包括空信号日），T+2 目标成熟后使用 `append_target_batch` 写入已提交触发的成熟目标与完整同日基准；来源可得时间、不可变规则字段、同股非重叠和哈希链都会校验。窗口结束后必须提交独立交易日文件、来源和 SHA-256，账本会核对文件日期与所有提交逐日相等；只有成功写入 `FINAL_LOCK.json` 后，才允许导出最终文件。终局导出的 CSV/JSON 以 `FINAL_LOCK` 为唯一真源，不能再由状态报告生成器覆盖。账本不联网、不补行情，也不读取历史诊断作为最终观察。

当前登记状态为 `FROZEN_RULE_PENDING_36_MONTH_FINAL`，窗口与股票范围已经独立冻结：使用 phase5 的 100 只 PIT 股票范围（名单 SHA-256 保存在登记中），最终窗口为 `2027-01-01..2029-12-31` 的三个完整自然年。账本拒绝任何与登记窗口、范围或规则版本不一致的正式追加。

当前状态为“待最终验证”。36 个月窗口尚未结束，完成后才在“通过”“未通过”“支持不足”三者中作最终分类；支持门槛中的 30 个日期指不同 `signal_date`，日期等权统计仍使用 `entry_date`；历史诊断结果不替代最终验证。
