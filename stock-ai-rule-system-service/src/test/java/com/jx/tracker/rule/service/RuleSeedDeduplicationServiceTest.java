package com.jx.tracker.rule.service;

import com.jx.tracker.domain.entity.CandidateRule;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.rule.engine.JsonRuleToDroolsCompiler;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class RuleSeedDeduplicationServiceTest {

    private static final String SEED_CODE = "R_DROOLS_BEARISH_GUARD_001";
    private static final String CANDIDATE_CODE = "CR_TREND_BEAR_GUARD_001";
    private static final String KNOWN_CANDIDATE = """
            {"conditions":[
              {"field":"data_status","operator":"eq","value":"normal"},
              {"field":"short_term_trend","operator":"in","value":["down","strong_down"]},
              {"field":"technical_status","operator":"eq","value":"bearish"}],
             "actions":{"bearish_score":55,"risk_score":15,"explanation":"研究提示"}}
            """;

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JsonRuleToDroolsCompiler compiler = new JsonRuleToDroolsCompiler();
    private final RuleSeedDeduplicationService service = new RuleSeedDeduplicationService(jdbcTemplate, compiler);

    @Test
    void publishingKnownCandidateDisablesSeedAndWritesAuditOnlyWhenConditionalUpdateSucceeds() {
        CandidateRule candidate = publishedCandidate(KNOWN_CANDIDATE);
        String published = compiler.compile(CANDIDATE_CODE, candidate.getProposedContent());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        service.disableSupersededSeedRule(candidate, published);

        verify(jdbcTemplate, times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void updateUsesSeedFingerprintAndPublishedContentFingerprint() {
        CandidateRule candidate = publishedCandidate(KNOWN_CANDIDATE);
        String published = compiler.compile(CANDIDATE_CODE, candidate.getProposedContent());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        service.disableSupersededSeedRule(candidate, published);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), arguments.capture());
        org.assertj.core.api.Assertions.assertThat(sql.getValue())
                .contains("SHA2(seed.rule_content, 256)")
                .contains("SHA2(published.rule_content, 256)")
                .contains("JSON_SEARCH(strategy.snapshot_json")
                .contains("NOT EXISTS (SELECT 1 FROM rule_version");
        org.assertj.core.api.Assertions.assertThat(arguments.getValue())
                .contains(SEED_CODE, CANDIDATE_CODE,
                        "f7b256f1533265e88b5f9aeff9a7a7a395c1e37bca2cd13fb0d214ed604e0d89");
    }

    @Test
    void unchangedSeedIsNotAuditedWhenSqlSafetyGuardPreservesIt() {
        CandidateRule candidate = publishedCandidate(KNOWN_CANDIDATE);
        String published = compiler.compile(CANDIDATE_CODE, candidate.getProposedContent());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        service.disableSupersededSeedRule(candidate, published);

        verify(jdbcTemplate, times(1)).update(anyString(), any(Object[].class));
    }

    @Test
    void changedCandidateConditionsNeverDisableSeed() {
        CandidateRule changed = publishedCandidate(KNOWN_CANDIDATE
                .replace("\"bearish\"", "\"bullish\""));

        when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class),
                org.mockito.ArgumentMatchers.eq(SEED_CODE))).thenReturn(1);

        assertThatThrownBy(() -> service.disableSupersededSeedRule(
                changed, compiler.compile(CANDIDATE_CODE, changed.getProposedContent())))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("人工复核");
    }

    @Test
    void changedScoreStillDisablesOverlappingSeed() {
        CandidateRule candidate = publishedCandidate(KNOWN_CANDIDATE
                .replace("\"bearish_score\":55", "\"bearish_score\":40"));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        service.disableSupersededSeedRule(
                candidate, compiler.compile(CANDIDATE_CODE, candidate.getProposedContent()));

        verify(jdbcTemplate).update(anyString(), any(Object[].class));
    }

    @Test
    void reorderedConditionsAndInValuesStillDisableOverlappingSeed() {
        String reordered = """
                {"conditions":[
                    {"field":"technical_status","operator":"eq","value":"bearish"},
                    {"field":"short_term_trend","operator":"in","value":["strong_down","down"]},
                    {"field":"data_status","operator":"eq","value":"normal"}],
                 "actions":{"bearish_score":55,"risk_score":15}}
                """;
        CandidateRule candidate = publishedCandidate(reordered);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        service.disableSupersededSeedRule(
                candidate, compiler.compile(CANDIDATE_CODE, candidate.getProposedContent()));

        verify(jdbcTemplate).update(anyString(), any(Object[].class));
    }

    @Test
    void changedPublishedDroolsNeverDisablesSeed() {
        CandidateRule candidate = publishedCandidate(KNOWN_CANDIDATE);
        String changed = compiler.compile(CANDIDATE_CODE, candidate.getProposedContent())
                .replace("new BigDecimal(\"55\")", "new BigDecimal(\"5\")");

        when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class),
                org.mockito.ArgumentMatchers.eq(SEED_CODE))).thenReturn(1);

        assertThatThrownBy(() -> service.disableSupersededSeedRule(candidate, changed))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("人工复核");
    }

    @Test
    void unpublishedCandidateDoesNotDisableSeed() {
        CandidateRule candidate = publishedCandidate(KNOWN_CANDIDATE);
        candidate.setStatus("approved");

        service.disableSupersededSeedRule(candidate, compiler.compile(CANDIDATE_CODE, candidate.getProposedContent()));

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void unrelatedSeedDoesNotQueryPublishedRule() {
        service.assertNoActiveDuplicate("R_CUSTOM", "edited");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void governanceRejectsCandidateActivationWhenUneditedSeedIsStillActive() {
        String published = compiler.compile(CANDIDATE_CODE, KNOWN_CANDIDATE);
        when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class),
                org.mockito.ArgumentMatchers.eq(SEED_CODE))).thenReturn(1);

        assertThatThrownBy(() -> service.assertNoActiveDuplicate(CANDIDATE_CODE, published))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不能重复启用");
    }

    @Test
    void governanceAllowsChangedCandidateContentWhenSeedIsInactive() {
        String changed = KNOWN_CANDIDATE.replace("\"bearish\"", "\"bullish\"");

        service.assertNoActiveDuplicate(CANDIDATE_CODE, compiler.compile(CANDIDATE_CODE, changed));
    }

    @Test
    void governanceRejectsEditedCandidateEvenWhenConditionsNoLongerMatchTemplate() {
        String changed = KNOWN_CANDIDATE.replace("\"bearish\"", "\"bullish\"");
        when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class),
                org.mockito.ArgumentMatchers.eq(SEED_CODE))).thenReturn(1);

        assertThatThrownBy(() -> service.assertNoActiveDuplicate(
                CANDIDATE_CODE, compiler.compile(CANDIDATE_CODE, changed)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不能重复启用");
    }

    private static CandidateRule publishedCandidate(String content) {
        return CandidateRule.builder()
                .candidateCode(CANDIDATE_CODE)
                .targetRuleCode(CANDIDATE_CODE)
                .status("published")
                .approvalStatus("approved")
                .backtestStatus("success")
                .proposedContent(content)
                .build();
    }
}
