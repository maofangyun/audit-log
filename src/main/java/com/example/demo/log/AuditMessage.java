package com.example.demo.log;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuditMessage {
    private Long id;
    private String action;
    private String resourceType;
    private String resourceId;
    private String status;
    private String details;
    private Object oldState;
    private Object newState;
    private java.util.List<String> diffs;
}
