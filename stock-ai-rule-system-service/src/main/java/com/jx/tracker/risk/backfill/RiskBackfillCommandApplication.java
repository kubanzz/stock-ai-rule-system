package com.jx.tracker.risk.backfill;

import com.jx.tracker.TrackerApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;

public final class RiskBackfillCommandApplication {

    private RiskBackfillCommandApplication() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        return run(args, actualArgs -> {
            SpringApplication application = new SpringApplication(TrackerApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            return application.run(actualArgs);
        });
    }

    static int run(String[] args, ContextLauncher launcher) {
        if (launcher == null) {
            return RiskBackfillExitCode.CONFIGURATION_ERROR.code();
        }
        try (ConfigurableApplicationContext context = launcher.launch(safeArguments(args))) {
            RiskBackfillCommandRunner runner = context.getBean(RiskBackfillCommandRunner.class);
            return runner.run().exitCode().code();
        } catch (RuntimeException exception) {
            return RiskBackfillExitCode.CONFIGURATION_ERROR.code();
        }
    }

    private static String[] safeArguments(String[] args) {
        String[] original = args == null ? new String[0] : args;
        String[] safe = Arrays.copyOf(original, original.length + 3);
        safe[original.length] = "--spring.main.web-application-type=none";
        safe[original.length + 1] = "--spring.task.scheduling.enabled=false";
        safe[original.length + 2] = "--stock-ai-rule.scheduler.daily-enabled=false";
        return safe;
    }

    @FunctionalInterface
    interface ContextLauncher {
        ConfigurableApplicationContext launch(String[] args);
    }
}
