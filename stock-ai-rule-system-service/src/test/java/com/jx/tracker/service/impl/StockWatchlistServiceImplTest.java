package com.jx.tracker.service.impl;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jx.tracker.domain.entity.StockWatchlist;
import com.jx.tracker.domain.entity.StockWatchlistItem;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockWatchlistServiceImplTest {

    @Test
    void exposesWatchlistBusinessFields() {
        assertEquals("my-follow", StockWatchlist.builder().poolCode("my-follow").build().getPoolCode());
        assertEquals("600519.SH", StockWatchlistItem.builder().symbol("600519.SH").build().getSymbol());
    }

    @Test
    void declaresMybatisPlusEntityMappings() throws NoSuchFieldException {
        assertTableMapping(StockWatchlist.class, "stock_watchlist");
        assertTableMapping(StockWatchlistItem.class, "stock_watchlist_item");

        assertAutoId(StockWatchlist.class);
        assertAutoId(StockWatchlistItem.class);

        assertTimeFieldMapping(StockWatchlist.class, "createdTime", "created_at", FieldFill.INSERT);
        assertTimeFieldMapping(StockWatchlist.class, "updatedTime", "updated_at", FieldFill.INSERT_UPDATE);
        assertTimeFieldMapping(StockWatchlistItem.class, "createdTime", "created_at", FieldFill.INSERT);
        assertTimeFieldMapping(StockWatchlistItem.class, "updatedTime", "updated_at", FieldFill.INSERT_UPDATE);
    }

    @Test
    void declaresWatchlistSchemaConstraints() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/db/stock_ai_rule_schema.sql")) {
            assertNotNull(input);
            String ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(ddl.contains("uk_stock_watchlist_pool_code (pool_code)"));
            assertTrue(ddl.contains("uk_stock_watchlist_item_watchlist_symbol (watchlist_id, symbol)"));
            assertTrue(ddl.contains(
                    "FOREIGN KEY (watchlist_id) REFERENCES stock_watchlist(id) ON DELETE CASCADE"
            ));
        }
    }

    private static void assertTableMapping(Class<?> entityType, String expectedTableName) {
        TableName tableName = entityType.getAnnotation(TableName.class);
        assertNotNull(tableName);
        assertEquals(expectedTableName, tableName.value());
    }

    private static void assertAutoId(Class<?> entityType) throws NoSuchFieldException {
        TableId tableId = entityType.getDeclaredField("id").getAnnotation(TableId.class);
        assertNotNull(tableId);
        assertEquals(IdType.AUTO, tableId.type());
    }

    private static void assertTimeFieldMapping(Class<?> entityType,
                                               String fieldName,
                                               String expectedColumn,
                                               FieldFill expectedFill) throws NoSuchFieldException {
        TableField tableField = entityType.getDeclaredField(fieldName).getAnnotation(TableField.class);
        assertNotNull(tableField);
        assertEquals(expectedColumn, tableField.value());
        assertEquals(expectedFill, tableField.fill());
    }
}
