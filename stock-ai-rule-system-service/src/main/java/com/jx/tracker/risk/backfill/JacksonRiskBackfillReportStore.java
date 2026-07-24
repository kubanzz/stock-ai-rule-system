package com.jx.tracker.risk.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class JacksonRiskBackfillReportStore implements RiskBackfillReportStore {

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");
    private static final Pattern URL_USER_INFO = Pattern.compile(
            "(?i)(https?://)[^\\s/@:]+:[^\\s/@]+@");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)((?:password|token|api[-_]?key|secret|tushare_token)\\s*[:=]\\s*)[^\\s,;\"}]+");

    private final ObjectMapper objectMapper;

    public JacksonRiskBackfillReportStore(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    @Override
    public Path write(Path directory, RiskBackfillReport report) throws IOException {
        if (directory == null || report == null || report.startedAt() == null
                || report.mode() == null) {
            throw new IllegalArgumentException("report directory and identity fields are required");
        }
        Files.createDirectories(directory);
        String fileName = fileName(report);
        Path target = directory.resolve(fileName);
        Path temporary = Files.createTempFile(directory, fileName + "-", ".tmp");
        try {
            JsonNode sanitized = sanitizeNode(objectMapper.valueToTree(report));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), sanitized);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public boolean hasPassedSampleGate(
            Path directory,
            String modelVersion,
            LocalDate endDate
    ) {
        if (directory == null || !Files.isDirectory(directory)
                || modelVersion == null || endDate == null) {
            return false;
        }
        try (var paths = Files.list(directory)) {
            List<Path> reports = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("risk-backfill-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path path : reports) {
                try {
                    RiskBackfillReport report = objectMapper.readValue(
                            path.toFile(), RiskBackfillReport.class);
                    if (report.isPassedSampleGateFor(modelVersion, endDate)) {
                        return true;
                    }
                } catch (IOException | RuntimeException ignored) {
                    // A damaged older report is not proof that the sample gate passed.
                }
            }
            return false;
        } catch (IOException exception) {
            return false;
        }
    }

    private String fileName(RiskBackfillReport report) {
        String runId = report.runId() == null ? "run"
                : report.runId().replaceAll("[^A-Za-z0-9-]", "");
        if (runId.isBlank()) {
            runId = "run";
        }
        String modelVersion = report.modelVersion() == null ? "model"
                : report.modelVersion().replaceAll("[^A-Za-z0-9-]", "");
        if (modelVersion.isBlank()) {
            modelVersion = "model";
        }
        String endDate = report.endDate() == null ? "undated"
                : report.endDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        return "risk-backfill-" + FILE_TIME.format(report.startedAt()) + "-"
                + report.mode().name().toLowerCase(Locale.ROOT) + "-"
                + endDate + "-"
                + modelVersion + "-" + runId + ".json";
    }

    private JsonNode sanitizeNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(sanitize(node.textValue()));
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> fields = new java.util.ArrayList<>();
            object.fieldNames().forEachRemaining(fields::add);
            fields.forEach(field -> object.set(field, sanitizeNode(object.get(field))));
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) {
                array.set(index, sanitizeNode(array.get(index)));
            }
        }
        return node;
    }

    private String sanitize(String text) {
        String withoutUserInfo = URL_USER_INFO.matcher(text)
                .replaceAll("$1[REDACTED]@");
        return SECRET_ASSIGNMENT.matcher(withoutUserInfo)
                .replaceAll("$1[REDACTED]");
    }
}
