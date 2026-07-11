package com.jx.tracker.market.data;

import com.jx.tracker.market.data.util.MarketCodeNormalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketCodeNormalizerTest {

    @Test
    void convertsKnownCodesAndNamesToDisplayNames() {
        assertThat(MarketCodeNormalizer.toDisplayName(" A股 ")).isEqualTo("A股");
        assertThat(MarketCodeNormalizer.toDisplayName("CN")).isEqualTo("A股");
        assertThat(MarketCodeNormalizer.toDisplayName("HK")).isEqualTo("港股");
        assertThat(MarketCodeNormalizer.toDisplayName(" 港股 ")).isEqualTo("港股");
        assertThat(MarketCodeNormalizer.toDisplayName("US")).isEqualTo("美股");
        assertThat(MarketCodeNormalizer.toDisplayName("unknown ")).isEqualTo("unknown");
        assertThat(MarketCodeNormalizer.toDisplayName(null)).isNull();
    }

    @Test
    void returnsStorageAliasesForKnownMarkets() {
        assertThat(MarketCodeNormalizer.aliases("CN")).containsExactly("A股", "CN");
        assertThat(MarketCodeNormalizer.aliases("港股")).containsExactly("港股", "HK");
        assertThat(MarketCodeNormalizer.aliases("US")).containsExactly("美股", "US");
        assertThat(MarketCodeNormalizer.aliases("other")).containsExactly("other");
    }

    @Test
    void comparesMarketsByDisplayMeaning() {
        assertThat(MarketCodeNormalizer.equivalent("A股", "CN")).isTrue();
        assertThat(MarketCodeNormalizer.equivalent(" 港股", "HK ")).isTrue();
        assertThat(MarketCodeNormalizer.equivalent("美股", "CN")).isFalse();
        assertThat(MarketCodeNormalizer.equivalent(null, null)).isTrue();
        assertThat(MarketCodeNormalizer.equivalent(null, "CN")).isFalse();
    }
}
