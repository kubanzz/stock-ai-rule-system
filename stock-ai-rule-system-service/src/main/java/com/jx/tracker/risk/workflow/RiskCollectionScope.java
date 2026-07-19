package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.model.RiskObjectKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 为采集断点生成可审计、顺序无关且适合 VARCHAR(128) 的稳定作用域键。 */
public final class RiskCollectionScope {

    private RiskCollectionScope() {
    }

    public static String key(List<RiskObjectKey> objects) {
        List<String> canonical = canonicalObjects(objects);
        String digest = HexFormat.of().formatHex(sha256(String.join("\n", canonical)));
        return "scope:v1:n=" + canonical.size() + ":sha256=" + digest;
    }

    /** Provider 旧契约使用的规范对象串，仅在内存调用边界使用，禁止作为数据库 scope_key。 */
    public static String providerKey(List<RiskObjectKey> objects) {
        return String.join(",", canonicalObjects(objects));
    }

    private static List<String> canonicalObjects(List<RiskObjectKey> objects) {
        if (objects == null || objects.isEmpty()) {
            throw new IllegalArgumentException("risk collection scope objects must not be empty");
        }
        return objects.stream()
                .map(object -> object.objectType().getCode() + ":" + object.objectId())
                .distinct()
                .sorted()
                .toList();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
