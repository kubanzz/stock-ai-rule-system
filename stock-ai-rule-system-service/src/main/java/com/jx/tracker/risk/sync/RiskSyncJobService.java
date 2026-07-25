package com.jx.tracker.risk.sync;

import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.risk.runtime.DefaultRiskAfterCloseWorkflow;
import com.jx.tracker.risk.runtime.RiskTradeDateResolver;
import com.jx.tracker.risk.workflow.RiskWorkflowRunSummary;
import org.springframework.core.task.TaskExecutor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class RiskSyncJobService {

    private static final String MARKET_SCOPE = "market:CN-A";
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)(password|token|secret|credential)(\\s*[=:]\\s*)[^\\s,;]+");
    private static final int MAX_ERROR_LENGTH = 240;

    private final DefaultRiskAfterCloseWorkflow workflow;
    private final RiskTradeDateResolver tradeDateResolver;
    private final TaskExecutor taskExecutor;
    private final Clock clock;
    private final Map<String, RiskSyncJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, String> activeJobIds = new ConcurrentHashMap<>();

    public RiskSyncJobService(
            DefaultRiskAfterCloseWorkflow workflow,
            RiskTradeDateResolver tradeDateResolver,
            TaskExecutor taskExecutor,
            Clock clock
    ) {
        if (workflow == null || tradeDateResolver == null || taskExecutor == null || clock == null) {
            throw new IllegalArgumentException("risk sync dependencies are required");
        }
        this.workflow = workflow;
        this.tradeDateResolver = tradeDateResolver;
        this.taskExecutor = taskExecutor;
        this.clock = clock;
    }

    public RiskSyncJob startMarketSync() {
        return start(MARKET_SCOPE, null);
    }

    public RiskSyncJob startStockSync(String symbol) {
        String normalized;
        try {
            normalized = workflow.normalizeStockSymbol(symbol);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ServiceException(clean(exception), 400);
        }
        return start("stock:" + normalized, normalized);
    }

    public Optional<RiskSyncJob> get(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(jobs.get(jobId));
    }

    public RiskSyncStatus status() {
        RiskSyncJob latestMarket = jobs.values().stream()
                .filter(job -> MARKET_SCOPE.equals(job.scopeKey()))
                .max(Comparator.comparing(RiskSyncJob::createdAt))
                .orElse(null);
        var active = jobs.values().stream()
                .filter(RiskSyncJob::active)
                .sorted(Comparator.comparing(RiskSyncJob::createdAt).reversed())
                .toList();
        return new RiskSyncStatus(latestMarket, active);
    }

    private synchronized RiskSyncJob start(String scopeKey, String symbol) {
        String activeId = activeJobIds.get(scopeKey);
        RiskSyncJob active = activeId == null ? null : jobs.get(activeId);
        if (active != null && active.active()) {
            return active;
        }
        String jobId = UUID.randomUUID().toString();
        LocalDateTime createdAt = LocalDateTime.now(clock);
        RiskSyncJob queued = new RiskSyncJob(
                jobId, scopeKey, "queued", "resolving_trade_date", 0,
                null, 0, 0, 0, 0, 0, null,
                createdAt, null, null
        );
        jobs.put(jobId, queued);
        activeJobIds.put(scopeKey, jobId);
        taskExecutor.execute(() -> run(jobId, symbol));
        return queued;
    }

    private void run(String jobId, String symbol) {
        RiskSyncJob queued = jobs.get(jobId);
        try {
            update(jobId, "running", "resolving_trade_date", 5, null, null, null);
            LocalDate tradeDate = tradeDateResolver.latestOpenDate(clock);
            update(jobId, "running", symbol == null ? "syncing_market" : "syncing_stock",
                    20, tradeDate, null, null);
            RiskWorkflowRunSummary summary = symbol == null
                    ? workflow.runManualMarket(tradeDate)
                    : workflow.runManualStock(tradeDate, symbol);
            String status = summary.unavailableDatasetCount() == 0
                    ? "succeeded" : "partial_success";
            String message = summary.unavailableDatasetCount() == 0
                    ? "同步完成"
                    : "同步完成，" + summary.unavailableDatasetCount() + " 个数据集暂不可用";
            complete(jobId, status, tradeDate, summary, message);
        } catch (RuntimeException exception) {
            update(jobId, "failed", "completed", 100, null, clean(exception), LocalDateTime.now(clock));
        } finally {
            activeJobIds.remove(queued.scopeKey(), jobId);
        }
    }

    private void complete(
            String jobId,
            String status,
            LocalDate tradeDate,
            RiskWorkflowRunSummary summary,
            String message
    ) {
        jobs.computeIfPresent(jobId, (ignored, current) -> new RiskSyncJob(
                current.jobId(), current.scopeKey(), status, "completed", 100,
                tradeDate, summary.observationCount(), summary.eventCount(),
                summary.snapshotCount(), summary.evidenceCount(),
                summary.unavailableDatasetCount(), message,
                current.createdAt(), current.startedAt(), LocalDateTime.now(clock)
        ));
    }

    private void update(
            String jobId,
            String status,
            String phase,
            int progress,
            LocalDate tradeDate,
            String message,
            LocalDateTime finishedAt
    ) {
        jobs.computeIfPresent(jobId, (ignored, current) -> new RiskSyncJob(
                current.jobId(), current.scopeKey(), status, phase, progress,
                tradeDate == null ? current.tradeDate() : tradeDate,
                current.observationCount(), current.eventCount(), current.snapshotCount(),
                current.evidenceCount(), current.unavailableDatasetCount(), message,
                current.createdAt(),
                current.startedAt() == null ? LocalDateTime.now(clock) : current.startedAt(),
                finishedAt
        ));
    }

    private String clean(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String value = root.getMessage();
        if (value == null || value.isBlank()) {
            value = root.getClass().getSimpleName();
        }
        value = SENSITIVE_VALUE.matcher(value.replaceAll("[\\r\\n\\t]+", " ")).replaceAll("$1$2***");
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
