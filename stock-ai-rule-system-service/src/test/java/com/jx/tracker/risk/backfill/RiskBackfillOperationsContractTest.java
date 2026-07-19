package com.jx.tracker.risk.backfill;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RiskBackfillOperationsContractTest {

    @Test
    void scriptUsesTheDedicatedNonWebMainAndAllSafetySwitches() throws Exception {
        String script = Files.readString(
                Path.of("scripts/risk-backfill.sh"), StandardCharsets.UTF_8);

        assertThat(script)
                .contains("com.jx.tracker.risk.backfill.RiskBackfillCommandApplication")
                .contains("--spring.main.web-application-type=none")
                .contains("RISK_WARNING_ENABLED")
                .contains("RISK_WARNING_BACKFILL_ENABLED")
                .contains("RISK_WARNING_BACKFILL_COMMAND_ENABLED")
                .contains("RISK_WARNING_BACKFILL_CONFIRMATION")
                .contains("RISK_WARNING_BACKFILL_END_DATE")
                .contains("RISK_WARNING_DERIVED_GATEWAY_BASE_URL")
                .doesNotContain("RISK_WARNING_BACKFILL_CONFIRMATION=BACKFILL_5Y")
                .doesNotContain("password=")
                .doesNotContain("TUSHARE_TOKEN=");
    }

    @Test
    void runbookCoversBackupGatesResumeAndReadOnlyAcceptance() throws Exception {
        String runbook = Files.readString(
                Path.of("../doc/风险模块/五年真实回填运行手册.md"),
                StandardCharsets.UTF_8);

        assertThat(runbook)
                .contains("数据库备份")
                .contains("SHA-256")
                .contains("sample")
                .contains("staged")
                .contains("full")
                .contains("BACKFILL_5Y")
                .contains("80%")
                .contains("规范化历史衍生网关")
                .contains("risk_ingestion_checkpoint")
                .contains("退出码")
                .contains("断点续跑")
                .contains("enforced = 0")
                .contains("/api/risks/overview")
                .contains("辅助决策")
                .contains("不构成投资建议");
    }
}
