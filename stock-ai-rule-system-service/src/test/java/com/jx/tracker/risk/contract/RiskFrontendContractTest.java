package com.jx.tracker.risk.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jx.tracker.risk.model.RiskDecisionSupportNotice;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskEvidence;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskLevelCount;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectDetail;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectListItem;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectRef;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskOverview;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskSnapshot;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskTrendPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class RiskFrontendContractTest {

    private final ObjectMapper objectMapper = objectMapper();

    @Test
    void javaWireFieldsExactlyMatchFrozenFrontendTypes() throws Exception {
        String frontendTypes = Files.readString(frontendTypesPath());
        assertThat(frontendTypes)
                .contains("export interface RiskEvidence")
                .contains("export interface RiskSnapshot")
                .contains("export interface RiskOverview")
                .contains("tradeDate: null | string;")
                .contains("export interface RiskObjectDetail")
                .contains("export interface RiskTrendPoint")
                .contains(RiskDecisionSupportNotice.TEXT);

        RiskEvidence evidence = evidence();
        RiskSnapshot snapshot = snapshot(evidence);
        RiskObjectListItem item = new RiskObjectListItem("贵州茅台", snapshot.object(), snapshot);
        RiskOverview overview = new RiskOverview(
                "1-5d", LocalDate.of(2026, 7, 18),
                List.of(new RiskLevelCount("critical", 1L)), null, List.of(item),
                RiskDecisionSupportNotice.TEXT
        );
        RiskObjectDetail detail = new RiskObjectDetail(
                item.name(), item.object(), item.snapshot(), List.of("credit_event"),
                List.of(new RiskObjectRef("sector", "BK0475")), List.of(snapshot)
        );
        RiskTrendPoint trend = new RiskTrendPoint(
                LocalDate.of(2026, 7, 18),
                new BigDecimal("70"), new BigDecimal("65"), new BigDecimal("55"),
                new BigDecimal("60"), new BigDecimal("45"), new BigDecimal("75"),
                "critical", new BigDecimal("0.90")
        );

        assertFields(objectMapper.valueToTree(evidence),
                "availableAt", "details", "dimension", "indicatorCode", "observedAt",
                "qualityStatus", "rawValue", "score", "source");
        assertFields(objectMapper.valueToTree(snapshot),
                "aScore", "cScore", "calculatedAt", "completeness", "evidence", "horizon",
                "level", "mScore", "modelVersion", "object", "riskConfidence", "sScore",
                "stage", "tScore", "totalScore", "tradeDate", "vScore",
                "conclusionStatus", "provisionalScore", "provisionalLevel", "dimensions",
                "dataAsOf", "staleTradingDays");
        assertFields(objectMapper.valueToTree(overview),
                "highRiskObjects", "horizon", "levelCounts", "marketSnapshot",
                "riskDisclaimer", "tradeDate");
        assertFields(objectMapper.valueToTree(item), "name", "object", "snapshot");
        assertFields(objectMapper.valueToTree(detail),
                "activeTriggers", "name", "object", "parentObjects", "snapshot", "snapshots");
        assertFields(objectMapper.valueToTree(trend),
                "aScore", "cScore", "completeness", "level", "sScore", "tScore",
                "totalScore", "tradeDate", "vScore");
    }

    private RiskEvidence evidence() {
        return new RiskEvidence(
                "T", "credit_event", new BigDecimal("12.5"), new BigDecimal("80"),
                LocalDateTime.of(2026, 7, 18, 15, 0), LocalDateTime.of(2026, 7, 18, 15, 30),
                "test", "available", Map.of("reason", "credit")
        );
    }

    private RiskSnapshot snapshot(RiskEvidence evidence) {
        return new RiskSnapshot(
                new RiskObjectRef("stock", "600519.SH"), "1-5d", LocalDate.of(2026, 7, 18),
                new BigDecimal("70"), new BigDecimal("65"), new BigDecimal("55"),
                new BigDecimal("60"), new BigDecimal("45"), new BigDecimal("1.05"),
                new BigDecimal("75"), "critical", "stampede", new BigDecimal("0.90"),
                new BigDecimal("0.88"), List.of(evidence), "risk-v1",
                LocalDateTime.of(2026, 7, 18, 16, 0)
        );
    }

    private void assertFields(JsonNode node, String... fields) {
        assertThat(StreamSupport.stream(
                ((Iterable<String>) () -> node.fieldNames()).spliterator(), false
        )).containsExactlyInAnyOrder(fields);
    }

    private Path frontendTypesPath() {
        Path fromServiceModule = Path.of(
                "..", "stock-ai-rule-system-ui", "apps", "web-antd", "src", "api", "stock", "risk", "types.ts"
        );
        if (Files.exists(fromServiceModule)) {
            return fromServiceModule;
        }
        return Path.of(
                "stock-ai-rule-system-ui", "apps", "web-antd", "src", "api", "stock", "risk", "types.ts"
        );
    }

    private static ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
