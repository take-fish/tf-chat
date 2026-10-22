package com.fishmatrix.chat.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * 独立 Flyway 迁移入口：由部署编排的一次性 Job 执行，不随 Web 应用启动。
 *
 * <p>使用迁移账号（MYSQL_MIGRATE_USER / MYSQL_MIGRATE_PASSWORD），业务容器只拿 DML 账号。
 * 通过 Spring Boot fat jar 的 PropertiesLauncher 调用：
 * {@code java -Dloader.main=... -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher}
 */
public final class DatabaseMigrator {

    private DatabaseMigrator() {
    }

    public static void main(String[] args) {
        try {
            String host = env("MYSQL_HOST", "127.0.0.1");
            String port = env("MYSQL_PORT", "3306");
            String database = required("MYSQL_DB");
            String user = required("MYSQL_MIGRATE_USER");
            String password = required("MYSQL_MIGRATE_PASSWORD");
            String locations = env("FLYWAY_LOCATIONS", "classpath:db/migration");

            String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";

            Flyway flyway = Flyway.configure()
                    .dataSource(url, user, password)
                    .locations(splitLocations(locations))
                    .table("flyway_schema_history")
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .validateOnMigrate(true)
                    .cleanDisabled(true)
                    .load();

            MigrateResult result = flyway.migrate();
            System.out.printf("[db-migrate] %s 完成：执行 %d 个迁移，目标版本 %s%n",
                    database, result.migrationsExecuted,
                    result.targetSchemaVersion == null ? "无" : result.targetSchemaVersion);
        } catch (RuntimeException exception) {
            System.err.println("[db-migrate] 迁移失败：" + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static String[] splitLocations(String locations) {
        String[] parts = locations.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " 未配置");
        }
        return value;
    }
}
