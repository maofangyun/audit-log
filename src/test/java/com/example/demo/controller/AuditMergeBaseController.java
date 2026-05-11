package com.example.demo.controller;

import com.example.demo.log.AuditLog;
import org.springframework.web.bind.annotation.PostMapping;

@AuditLog(action = "PARENT_ACTION", resourceType = "PARENT_TYPE", oldObjectSpEL = "'PARENT_OLD'", newObjectSpEL = "'PARENT_NEW'")
public abstract class AuditMergeBaseController {
    @PostMapping("/test/merge")
    public abstract String handle();
}
