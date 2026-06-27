package com.jx.tracker.domain.dto;

import java.time.LocalDate;
import java.util.List;

public class DailyWorkflowTriggerDto {

    private LocalDate tradeDate;

    private List<String> symbols;

    private Boolean dryRun;

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }

    public List<String> getSymbols() {
        return symbols;
    }

    public void setSymbols(List<String> symbols) {
        this.symbols = symbols;
    }

    public Boolean getDryRun() {
        return dryRun;
    }

    public void setDryRun(Boolean dryRun) {
        this.dryRun = dryRun;
    }
}
