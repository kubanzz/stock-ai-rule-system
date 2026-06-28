package com.jx.tracker.market.data;

import com.jx.tracker.market.data.util.SymbolNormalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolNormalizerTest {

    @Test
    void normalizesMainlandHongKongAndUsSymbolsToInternalFormat() {
        assertThat(SymbolNormalizer.normalize("sz000001")).isEqualTo("000001.SZ");
        assertThat(SymbolNormalizer.normalize("SH600000")).isEqualTo("600000.SH");
        assertThat(SymbolNormalizer.normalize("00700.hk")).isEqualTo("00700.HK");
        assertThat(SymbolNormalizer.normalize("aapl")).isEqualTo("AAPL.US");
        assertThat(SymbolNormalizer.normalize("AAPL.US")).isEqualTo("AAPL.US");
    }

    @Test
    void parsesMarketAndExchangeFromInternalSymbols() {
        assertThat(SymbolNormalizer.parseMarket("000001.SZ")).isEqualTo("CN");
        assertThat(SymbolNormalizer.parseExchange("000001.SZ")).isEqualTo("SZ");
        assertThat(SymbolNormalizer.parseMarket("00700.HK")).isEqualTo("HK");
        assertThat(SymbolNormalizer.parseExchange("00700.HK")).isEqualTo("HK");
        assertThat(SymbolNormalizer.parseMarket("AAPL.US")).isEqualTo("US");
        assertThat(SymbolNormalizer.parseExchange("AAPL.US")).isEqualTo("US");
    }
}
