package com.jx.tracker.risk.backfill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskBackfillSampleSelectorTest {

    private final RiskBackfillSampleSelector selector = new RiskBackfillSampleSelector();

    @Test
    void samplesAcrossTheWholeSortedUniverseDeterministically() {
        List<String> universe = IntStream.rangeClosed(1, 100)
                .mapToObj(i -> "%06d.SH".formatted(i)).toList();

        List<String> sample = selector.select(universe, List.of(), 5);

        assertThat(sample).containsExactly(
                "000001.SH", "000026.SH", "000051.SH", "000075.SH", "000100.SH");
        assertThat(selector.select(universe, List.of(), 5)).isEqualTo(sample);
    }

    @Test
    void normalizesSortsAndDeduplicatesExplicitSymbols() {
        List<String> sample = selector.select(
                List.of("600519.SH", "000001.SZ", "300750.SZ"),
                List.of("sz300750", "600519.sh", "SZ300750"),
                50);

        assertThat(sample).containsExactly("300750.SZ", "600519.SH");
    }

    @Test
    void rejectsExplicitSymbolsOutsideTheActiveUniverse() {
        assertThatThrownBy(() -> selector.select(
                List.of("600519.SH"), List.of("000001.SZ"), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active A-share universe")
                .hasMessageContaining("000001.SZ");
    }

    @Test
    void returnsTheWholeUniverseWhenItIsSmallerThanTheSample() {
        assertThat(selector.select(
                List.of("600519.SH", "000001.SZ", "300750.SZ"), List.of(), 50))
                .containsExactly("000001.SZ", "300750.SZ", "600519.SH");
    }

    @Test
    void rejectsEmptyUniverseAndInvalidSampleSize() {
        assertThatThrownBy(() -> selector.select(List.of(), List.of(), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active A-share universe");
        assertThatThrownBy(() -> selector.select(List.of("600519.SH"), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleSize");
    }
}
