package com.example.demo.controller;

import com.example.demo.log.AuditLog;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuditMergeChildController extends AuditMergeBaseController {
    @Override
    @AuditLog(action = "CHILD_ACTION", resourceType = "CHILD_TYPE")
    public String handle() {
        return "CHILD_SUCCESS";
    }
}
