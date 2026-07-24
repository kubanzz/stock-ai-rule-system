package com.jx.tracker.risk.backfill;

import com.jx.tracker.TrackerApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;

public final class RiskBackfillCommandApplication {

    private static final List<String> SAFETY_ARGUMENTS = List.of(
            "--spring.main.web-application-type=none",
            "--spring.task.scheduling.enabled=false",
            "--stock-ai-rule.scheduler.daily-enabled=false");

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
        List<String> safe = new ArrayList<>(original.length + SAFETY_ARGUMENTS.size());
        for (String argument : original) {
            if (!isSafetyArgument(argument)) {
                safe.add(argument);
            }
        }
        safe.addAll(SAFETY_ARGUMENTS);
        return safe.toArray(String[]::new);
    }

    private static boolean isSafetyArgument(String argument) {
        return argument != null && SAFETY_ARGUMENTS.stream()
                .map(value -> value.substring(0, value.indexOf('=') + 1))
                .anyMatch(argument::startsWith);
    }

    @FunctionalInterface
    interface ContextLauncher {
        ConfigurableApplicationContext launch(String[] args);
    }
}
