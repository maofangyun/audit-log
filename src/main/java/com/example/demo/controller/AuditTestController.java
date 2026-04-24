package com.example.demo.controller;

import com.example.demo.log.AuditContextHolder;
import com.example.demo.log.AuditLog;
import com.example.demo.service.ClaimCheckService;
import com.fasterxml.jackson.databind.JsonNode;
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
 * <p>提供用户更新、审计日志查询、审计详情懒加载三个端点，
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
    @AuditLog(
        action = "UPDATE_USER",
        resourceType = "USER",
        resourceIdSpEL = "#req.id",
        newObjectSpEL = "#result"
    )
    @PostMapping("/user/update")
    public UserDto updateUser(@RequestBody UserDto req) {
        // 1. 从"数据库"中查询修改前的旧数据并冻结快照
        UserDto oldEntity = fetchUserFromDb(req.getId());
        AuditContextHolder.setOldObject(oldEntity);

        // 2. 执行业务逻辑（此处以直接返回请求体模拟更新）
        return req;
    }

    // ── 审计日志查询接口 ───────────────────────────────────────────

    /**
     * 基于业务主键快速查询审计日志列表（毫秒级响应）。
     *
     * @param bizId 业务主键
     * @return 审计日志摘要列表（不含 details）
     */
    @GetMapping("/audit/search")
    public List<Map<String, Object>> search(@RequestParam String bizId) {
        String sql = "SELECT id, timestamp, action, resource_type, resource_id, status, is_large_payload "
                   + "FROM audit_logs WHERE business_id = ? ORDER BY timestamp DESC";
        return jdbcTemplate.queryForList(sql, bizId);
    }

    /**
     * 懒加载审计详情：若为大对象则实时从 MinIO 拉取，否则直接返回数据库存储的 JSON。
     *
     * @param id 审计日志主键
     * @return 审计详情（原始对象或 MinIO 下载内容）
     */
    @GetMapping("/audit/detail/{id}")
    public Object getDetail(@PathVariable Long id) {
        String sql = "SELECT details, is_large_payload FROM audit_logs WHERE id = ?";
        Map<String, Object> log = jdbcTemplate.queryForMap(sql, id);

        boolean isLarge = Boolean.TRUE.equals(log.get("is_large_payload"));
        Object detailsObj = log.get("details");

        if (isLarge) {
            return fetchLargePayload(detailsObj);
        }
        return detailsObj;
    }

    // ── 私有辅助方法 ───────────────────────────────────────────────

    /**
     * 模拟从数据库查询已有用户数据（作为审计 before 快照）。
     */
    private UserDto fetchUserFromDb(String id) {
        return new UserDto(id, "Tom", 17);
    }

    /**
     * 解析 Claim Check 指针并从 MinIO 下载大对象.
     */
    private Object fetchLargePayload(Object detailsObj) {
        try {
            String minioUrl;
            if (detailsObj instanceof Map) {
                // 情况 A：JDBC 驱动已自动将 JSON 转为 Map
                minioUrl = (String) ((Map<?, ?>) detailsObj).get("_url");
            } else {
                // 情况 B：返回的是 String 或 PGobject，需 Jackson 解析
                JsonNode node = objectMapper.readTree(detailsObj.toString());
                // 兼容处理：若为双重转义字符串则再解析一次
                if (node.isTextual()) {
                    node = objectMapper.readTree(node.asText());
                }
                minioUrl = node.get("_url").asText();
            }

            if (minioUrl == null) {
                return "Error: MinIO URL not found in details";
            }
            return claimCheckService.downloadPayload(minioUrl);
        } catch (Exception e) {
            return "Error restoring payload: " + e.getMessage();
        }
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
