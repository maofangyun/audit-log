package com.example.demo.controller;

import com.example.demo.log.AuditContextHolder;
import com.example.demo.log.AuditLog;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuditTestController {

    public static class UserDto {
        private String id;
        private String name;
        private int age;

        public UserDto() {}

        public UserDto(String id, String name, int age) {
            this.id = id;
            this.name = name;
            this.age = age;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
    }

    // Mock Database for Old State
    private UserDto fetchExtantUserFromDB(String id) {
        return new UserDto(id, "Tom", 17);
    }

    @AuditLog(
        action = "UPDATE_USER", 
        resourceType = "USER", 
        resourceIdSpEL = "#req.id",
        newObjectSpEL = "#result" // '#result' automatically captures the returned updated object
    )
    @PostMapping("/test/user/update")
    public UserDto updateUser(@RequestBody UserDto req) {
        // 1. Fetch old object from "DB"
        UserDto oldEntity = fetchExtantUserFromDB(req.getId());
        
        // 2. Freeze the old object for auditing
        AuditContextHolder.setOldObject(oldEntity);
        
        // 3. Perform business logic update (simulated by returning req directly)
        return req;
    }
}
