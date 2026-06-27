package com.jx.tracker.market.data.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MarketDataImportResultDto<T> {

    private int totalRows;

    private int insertedRows;

    private int updatedRows;

    private final List<T> acceptedRows = new ArrayList<>();

    private final List<RejectedMarketDataRowDto> rejectedRows = new ArrayList<>();

    public int getRejectedCount() {
        return rejectedRows.size();
    }

    public void accept(T row) {
        totalRows++;
        acceptedRows.add(row);
    }

    public void reject(int rowNumber, String symbol, String reason) {
        totalRows++;
        rejectedRows.add(new RejectedMarketDataRowDto(rowNumber, symbol, reason));
    }

    public void markInserted() {
        insertedRows++;
    }

    public void markUpdated() {
        updatedRows++;
    }
}
