package com.example.demo.log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class AuditContextHolder {
    private static final ThreadLocal<JsonNode> oldObjectHolder = new ThreadLocal<>();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void setOldObject(Object oldObject) {
        try {
            oldObjectHolder.set(oldObject != null ? objectMapper.valueToTree(oldObject) : null);
        } catch (Exception e) {
            oldObjectHolder.set(null);
        }
    }

    public static JsonNode getOldObject() {
        return oldObjectHolder.get();
    }

    public static void clear() {
        oldObjectHolder.remove();
    }
}
