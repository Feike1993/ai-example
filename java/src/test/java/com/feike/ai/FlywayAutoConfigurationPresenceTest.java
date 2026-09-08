package com.feike.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守住一个只会在运行期暴露的依赖陷阱。
 * <p>
 * Spring Boot 4 把 {@code FlywayAutoConfiguration} 从 {@code spring-boot-autoconfigure}
 * 拆进了独立的 {@code spring-boot-flyway} 模块。只依赖 {@code org.flywaydb:flyway-core}
 * 会拿到库本身却没有自动配置：应用照常启动、日志一句不说、迁移一条不跑，
 * 直到某个请求撞上不存在的表才炸。这正是它出过的事故。
 * <p>
 * 所以这里不测 Flyway 的行为（那是 {@code ProductionChatSessionStoreIT} 的事），
 * 只测「自动配置真的在 classpath 上」——依赖被人改回裸 flyway-core 时立刻失败，
 * 而不是等到线上第一个请求。
 */
@DisplayName("Flyway 自动配置存在性")
class FlywayAutoConfigurationPresenceTest {

    private static final String AUTO_CONFIG =
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration";

    @Test
    void flywayAutoConfigurationShouldBeOnClasspath() {
        assertDoesNotThrow(
            () -> Class.forName(AUTO_CONFIG),
            "缺少 spring-boot-starter-flyway：迁移会静默不执行，别只加 org.flywaydb:flyway-core"
        );
    }

    @Test
    void flywayAutoConfigurationShouldBeRegisteredForImport() throws IOException {
        // 类在 classpath 上还不够：Boot 只加载 AutoConfiguration.imports 里登记过的
        String imports = readAll("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertTrue(imports.contains(AUTO_CONFIG), "FlywayAutoConfiguration 未登记为自动配置：\n" + imports);
    }

    @Test
    void migrationScriptsShouldBePackaged() {
        assertNotNull(
            resource("db/migration/V1__baseline_chat_session_message.sql"),
            "V1 迁移脚本未打进 classpath"
        );
        assertNotNull(
            resource("db/migration/V2__production_chat_session.sql"),
            "V2 迁移脚本未打进 classpath"
        );
    }

    private static InputStream resource(String path) {
        return FlywayAutoConfigurationPresenceTest.class.getClassLoader().getResourceAsStream(path);
    }

    /** 同名资源在多个 jar 里都有，全部拼起来再找。 */
    private static String readAll(String path) throws IOException {
        StringBuilder all = new StringBuilder();
        var urls = FlywayAutoConfigurationPresenceTest.class.getClassLoader().getResources(path);
        while (urls.hasMoreElements()) {
            try (InputStream in = urls.nextElement().openStream()) {
                all.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            }
        }
        return all.toString();
    }
}
