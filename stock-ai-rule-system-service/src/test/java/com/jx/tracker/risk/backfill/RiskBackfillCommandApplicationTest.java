package com.jx.tracker.risk.backfill;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskBackfillCommandApplicationTest {

    @Test
    void returnsRunnerExitCodeAndAlwaysClosesTheContext() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        RiskBackfillCommandRunner runner = mock(RiskBackfillCommandRunner.class);
        RiskBackfillReport report = mock(RiskBackfillReport.class);
        AtomicReference<String[]> launchedArguments = new AtomicReference<>();
        when(context.getBean(RiskBackfillCommandRunner.class)).thenReturn(runner);
        when(runner.run()).thenReturn(new RiskBackfillCommandResult(
                RiskBackfillExitCode.SAMPLE_GATE_REJECTED, null, report));

        int exitCode = RiskBackfillCommandApplication.run(
                new String[]{
                        "--example=value",
                        "--stock-ai-rule.scheduler.daily-enabled=true"
                }, arguments -> {
                    launchedArguments.set(arguments);
                    return context;
                });

        assertThat(exitCode).isEqualTo(RiskBackfillExitCode.SAMPLE_GATE_REJECTED.code());
        assertThat(launchedArguments.get())
                .endsWith(
                        "--spring.main.web-application-type=none",
                        "--spring.task.scheduling.enabled=false",
                        "--stock-ai-rule.scheduler.daily-enabled=false");
        verify(context).close();
    }

    @Test
    void mapsContextStartupFailureToConfigurationExitCode() {
        int exitCode = RiskBackfillCommandApplication.run(
                new String[0], ignored -> {
                    throw new IllegalStateException("context failed");
                });

        assertThat(exitCode).isEqualTo(RiskBackfillExitCode.CONFIGURATION_ERROR.code());
    }
}
