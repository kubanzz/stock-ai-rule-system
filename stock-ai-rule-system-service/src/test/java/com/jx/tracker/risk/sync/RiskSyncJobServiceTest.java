package com.jx.tracker.risk.sync;

import com.jx.tracker.risk.runtime.DefaultRiskAfterCloseWorkflow;
import com.jx.tracker.risk.runtime.RiskTradeDateResolver;
import com.jx.tracker.risk.workflow.RiskWorkflowRunSummary;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskSyncJobServiceTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 24);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-25T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void reusesActiveMarketJobAndPublishesPartialSuccess() {
        DefaultRiskAfterCloseWorkflow workflow = mock(DefaultRiskAfterCloseWorkflow.class);
        RiskTradeDateResolver tradeDateResolver = mock(RiskTradeDateResolver.class);
        when(tradeDateResolver.latestOpenDate(CLOCK)).thenReturn(TRADE_DATE);
        when(workflow.runManualMarket(TRADE_DATE))
                .thenReturn(new RiskWorkflowRunSummary(20, 3, 6, 15, 0, 2, 1));
        QueuedExecutor executor = new QueuedExecutor();
        RiskSyncJobService service = new RiskSyncJobService(
                workflow, tradeDateResolver, executor, CLOCK);

        RiskSyncJob first = service.startMarketSync();
        RiskSyncJob second = service.startMarketSync();

        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(first.status()).isEqualTo("queued");
        assertThat(first.scopeKey()).isEqualTo("market:CN-A");
        executor.runNext();
        assertThat(service.get(first.jobId())).get().satisfies(job -> {
            assertThat(job.status()).isEqualTo("partial_success");
            assertThat(job.phase()).isEqualTo("completed");
            assertThat(job.tradeDate()).isEqualTo(TRADE_DATE);
            assertThat(job.progress()).isEqualTo(100);
            assertThat(job.unavailableDatasetCount()).isEqualTo(1);
            assertThat(job.message()).contains("1 个数据集");
        });
        assertThat(service.status().latestMarketJob().jobId()).isEqualTo(first.jobId());
        assertThat(service.status().activeJobs()).isEmpty();
        verify(workflow).runManualMarket(TRADE_DATE);
    }

    @Test
    void stockSyncNormalizesSymbolAndUsesSeparateScope() {
        DefaultRiskAfterCloseWorkflow workflow = mock(DefaultRiskAfterCloseWorkflow.class);
        RiskTradeDateResolver tradeDateResolver = mock(RiskTradeDateResolver.class);
        when(workflow.normalizeStockSymbol("600519")).thenReturn("600519.SH");
        when(tradeDateResolver.latestOpenDate(CLOCK)).thenReturn(TRADE_DATE);
        when(workflow.runManualStock(TRADE_DATE, "600519.SH"))
                .thenReturn(new RiskWorkflowRunSummary(8, 2, 9, 12, 0, 1, 0));
        QueuedExecutor executor = new QueuedExecutor();
        RiskSyncJobService service = new RiskSyncJobService(
                workflow, tradeDateResolver, executor, CLOCK);

        RiskSyncJob job = service.startStockSync("600519");

        assertThat(job.scopeKey()).isEqualTo("stock:600519.SH");
        executor.runNext();
        assertThat(service.get(job.jobId())).get()
                .extracting(RiskSyncJob::status)
                .isEqualTo("succeeded");
        verify(workflow).runManualStock(TRADE_DATE, "600519.SH");
    }

    @Test
    void rejectsAnotherScopeWhileSharedRiskDataSyncIsActive() {
        DefaultRiskAfterCloseWorkflow workflow = mock(
                DefaultRiskAfterCloseWorkflow.class);
        RiskTradeDateResolver tradeDateResolver = mock(
                RiskTradeDateResolver.class);
        when(workflow.normalizeStockSymbol("600519"))
                .thenReturn("600519.SH");
        QueuedExecutor executor = new QueuedExecutor();
        RiskSyncJobService service = new RiskSyncJobService(
                workflow, tradeDateResolver, executor, CLOCK);

        RiskSyncJob marketJob = service.startMarketSync();

        assertThatThrownBy(() -> service.startStockSync("600519"))
                .isInstanceOf(com.jx.tracker.exception.ServiceException.class)
                .hasMessageContaining("风险数据同步任务");
        assertThat(service.startMarketSync().jobId())
                .isEqualTo(marketJob.jobId());
    }

    private static final class QueuedExecutor implements TaskExecutor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
