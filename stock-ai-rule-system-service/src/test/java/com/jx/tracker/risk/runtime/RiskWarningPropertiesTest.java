package com.jx.tracker.risk.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

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

    @Test
    void keepsLocalConfigurationOutsideMavenResourcesAndActivatesItsProfileByDefault() throws IOException {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties application = yaml.getObject();
        Path moduleDirectory = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        String gitignore = Files.readString(moduleDirectory.getParent().resolve(".gitignore"));

        assertThat(application).isNotNull();
        assertThat(application.getProperty("spring.profiles.active")).isEqualTo("${SPRING_PROFILES_ACTIVE:dev,local}");
        assertThat(gitignore)
                .contains("/stock-ai-rule-system-service/config/application-local.yml")
                .doesNotContain("/stock-ai-rule-system-service/src/main/resources/application-local.yml");
    }
}
