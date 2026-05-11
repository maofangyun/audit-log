package com.example.demo.controller;

import com.example.demo.log.AuditContextHolder;
import com.example.demo.log.AuditLog;
import com.example.demo.service.ClaimCheckService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 审计日志功能测试控制器
 *
 * <p>
 * 提供用户更新、审计日志查询、审计详情懒加载三个端点，
 * 用于演示和验证 Claim Check 模式的完整链路。
 */
@RestController
@RequestMapping("/test")
public class AuditTestController {

    private final JdbcTemplate jdbcTemplate;
    private final ClaimCheckService claimCheckService;
    private final ObjectMapper objectMapper;

    public AuditTestController(JdbcTemplate jdbcTemplate,
            ClaimCheckService claimCheckService,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.claimCheckService = claimCheckService;
        this.objectMapper = objectMapper;
    }

    // ── 用户更新接口 ───────────────────────────────────────────────

    /**
     * 模拟用户更新操作，触发 @AuditLog 切面记录审计日志。
     */
    @AuditLog(action = "UPDATE_USER", resourceType = "USER", resourceIdSpEL = "#req.id", businessKeySpEL = "{'userId': #req.id}", newObjectSpEL = "#result")
    @PostMapping("/user/update")
    public UserDto updateUser(@RequestBody UserDto req) {
        // 1. 从"数据库"中查询修改前的旧数据并冻结快照
        UserDto oldEntity = fetchUserFromDb(req.getId());
        AuditContextHolder.setOldObject(oldEntity);

        // 2. 执行业务逻辑（此处以直接返回请求体模拟更新）
        return req;
    }

    // ── SpEL Bean 方法调用测试接口 ──────────────────────────────────

    /**
     * 测试在 SpEL 中调用 Spring 容器里注册的 Bean 的方法。
     */
    @AuditLog(action = "TEST_SPEL_BEAN", resourceType = "TEST", resourceIdSpEL = "#id.toString()", oldObjectSpEL = "@testAuditHelper.getOldValue(#id, #type)", newObjectSpEL = "@testAuditHelper.getNewValue(#result)")
    @PostMapping("/spel/bean")
    public String testSpelBean(@RequestParam Long id, @RequestParam String type) {
        return "RESULT_" + id;
    }

    // ── 审计日志查询接口 ───────────────────────────────────────────

    /**
     * 基于业务主键快速查询审计日志列表（毫秒级响应）。
     *
     * @param bizId 业务主键
     * @return 审计日志摘要列表
     */
    @GetMapping("/audit/search")
    public List<Map<String, Object>> search(@RequestParam String bizId) {
        // 构建用于 JSONB 包含查询的参数，例如：{"userId": "1"}
        String jsonbParam = String.format("{\"userId\": \"%s\"}", bizId);
        String sql = "SELECT id, timestamp, action, resource_type, resource_id, status "
                + "FROM audit_logs WHERE business_key @> ?::jsonb ORDER BY timestamp DESC";
        return jdbcTemplate.queryForList(sql, jsonbParam);
    }

    /**
     * 查询审计详情：从 audit_log_items 子表获取 before / after 明细。
     * 若 payload 为 MinIO 指针，则实时从对象存储还原原始对象。
     *
     * @param id 审计日志主键
     * @return before / after 明细列表
     */
    @GetMapping("/audit/detail/{id}")
    public Object getDetail(@PathVariable Long id) {
        String sql = "SELECT item_index, direction, item_type, payload::text AS payload "
                + "FROM audit_log_items WHERE audit_log_id = ? ORDER BY direction, item_index";
        List<Map<String, Object>> items = jdbcTemplate.queryForList(sql, id);

        // 对每一个 item 判断是否为 MinIO 指针，若是则还原
        items.forEach(item -> {
            String payloadStr = (String) item.get("payload");
            if (payloadStr != null && payloadStr.contains("\"_storage\"")) {
                try {
                    com.fasterxml.jackson.databind.JsonNode node =
                            objectMapper.readTree(payloadStr);
                    String minioUrl = node.get("_url").asText();
                    item.put("payload", claimCheckService.downloadPayload(minioUrl));
                } catch (Exception e) {
                    item.put("payload", "Error restoring payload: " + e.getMessage());
                }
            }
        });
        return items;
    }

    // ── 私有辅助方法 ───────────────────────────────────────────────

    /**
     * 模拟从数据库查询已有用户数据（作为审计 before 快照）。
     */
    private UserDto fetchUserFromDb(String id) {
        return new UserDto(id, "Tom", 17);
    }


    // ── 内部 DTO ──────────────────────────────────────────────────

    /**
     * 用户数据传输对象（仅用于测试演示）。
     */
    public static class UserDto {

        private String id;
        private String name;
        private int age;

        public UserDto() {
        }

        public UserDto(String id, String name, int age) {
            this.id = id;
            this.name = name;
            this.age = age;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }
}
