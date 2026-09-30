package com.jx.tracker.scheduler;

import com.jx.tracker.service.SignalBackfillService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * Daily and manual catch-up use the same gap detection and signal calculation.
 */
@Component
public class DailyQuoteSyncScheduledTask {

    private final SignalBackfillService signalBackfillService;

    @Value("${stock-ai-rule.scheduler.quote-sync-enabled:true}")
    private boolean quoteSyncEnabled;

    public DailyQuoteSyncScheduledTask(SignalBackfillService signalBackfillService) {
        this.signalBackfillService = signalBackfillService;
    }

    @Scheduled(cron = "${stock-ai-rule.scheduler.quote-sync-cron:0 15 18 * * MON-FRI}",
            zone = "${stock-ai-rule.scheduler.zone:Asia/Shanghai}")
    public void syncWatchlistQuotes() {
        if (quoteSyncEnabled) {
            signalBackfillService.start();
        }
    }
}
