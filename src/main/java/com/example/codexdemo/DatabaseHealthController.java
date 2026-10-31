package com.example.codexdemo;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 单独检查数据库连接，保留原有 Java 健康接口的契约。 */
@RestController
public class DatabaseHealthController {
    private final DataSource dataSource;

    public DatabaseHealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/api/health/database")
    public ResponseEntity<DatabaseHealthResponse> health() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(2)) {
                return ResponseEntity.ok(new DatabaseHealthResponse("ok", "数据库连接正常"));
            }
        } catch (SQLException exception) {
            // 错误作为 503 显式返回；不把驱动异常中的地址或凭据暴露到浏览器。
            return unavailable();
        }
        return unavailable();
    }

    private ResponseEntity<DatabaseHealthResponse> unavailable() {
        return ResponseEntity.status(503)
                .body(new DatabaseHealthResponse("unavailable", "数据库暂不可用"));
    }

    public record DatabaseHealthResponse(String status, String message) {}
}
