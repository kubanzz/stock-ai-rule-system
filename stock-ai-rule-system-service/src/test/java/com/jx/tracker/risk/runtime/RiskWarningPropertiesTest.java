package com.jx.tracker.risk.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskWarningPropertiesTest {

    @Test
    void defaultsToAkToolsWithCninfoAnnouncementFallbackEnabled() {
        RiskWarningProperties properties = new RiskWarningProperties();

        properties.validateSource(null);

        assertThat(properties.requiredPrimarySource()).isEqualTo("aktools");
        assertThat(properties.getSource().isTushareEnabled()).isFalse();
        assertThat(properties.getSource().isCninfoAnnouncementFallbackEnabled()).isTrue();
    }

    @Test
    void normalizesTusharePrimarySourceWhenExplicitlyEnabledAndTokenIsPresent() {
        RiskWarningProperties properties = new RiskWarningProperties();
        properties.getSource().setPrimary("  TUSHARE  ");
        properties.getSource().setTushareEnabled(true);

        properties.validateSource("  test-token  ");

        assertThat(properties.requiredPrimarySource()).isEqualTo("tushare");
    }

    @Test
    void rejectsAnUnsupportedPrimarySource() {
        RiskWarningProperties properties = new RiskWarningProperties();
        properties.getSource().setPrimary("csv");

        assertThatThrownBy(properties::requiredPrimarySource)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("risk warning source primary must be one of: aktools, tushare");
    }

    @Test
    void failsFastWhenTushareIsEnabledWithoutAToken() {
        RiskWarningProperties properties = new RiskWarningProperties();
        properties.getSource().setTushareEnabled(true);

        assertThatThrownBy(() -> properties.validateSource(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("risk warning TuShare token must be configured when TuShare is enabled");
    }
}
