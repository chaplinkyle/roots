package com.chaplin.roots.examples.kanban;

import com.chaplin.roots.AuthorizationPolicy;
import com.chaplin.roots.Response;
import com.chaplin.roots.spring.boot.EnableRoots;
import com.chaplin.roots.spring.boot.RootsConfigCustomizer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import javax.sql.DataSource;
import java.util.Arrays;

@SpringBootApplication
@EnableRoots(Application.class)
public class Application {
    public static void main(String[] args) {
        if (Arrays.equals(args, new String[]{"--migrate"})) {
            try (var pool = pool(new StandardEnvironment())) { migrations(pool).migrate(); }
            return;
        }
        SpringApplication.run(Application.class, args);
    }

    static HikariDataSource pool(Environment env) {
        var config = new HikariConfig();
        config.setJdbcUrl(required(env, "KANBAN_JDBC_URL"));
        config.setUsername(env.getProperty("KANBAN_JDBC_USER", ""));
        config.setPassword(env.getProperty("KANBAN_JDBC_PASSWORD", ""));
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(2000);
        config.setValidationTimeout(1000);
        config.setPoolName("kanban");
        return new HikariDataSource(config);
    }

    static Flyway migrations(DataSource pool) {
        return Flyway.configure().dataSource(pool).locations("classpath:db/migration")
                .cleanDisabled(true).load();
    }

    static String required(Environment env, String name) {
        var value = env.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Configure " + name);
        return value;
    }

    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(Environment env) {
        var pool = pool(env);
        try {
            var migrations = migrations(pool);
            migrations.validate();
            if (migrations.info().pending().length != 0)
                throw new IllegalStateException("Run the reviewed --migrate step before application startup");
            return pool;
        } catch (RuntimeException failure) { pool.close(); throw failure; }
    }

    @Bean TaskRepository tasks(DataSource pool) { return new TaskRepository(pool); }

    @Bean HealthIndicator kanbanDatabase(DataSource pool) {
        return () -> {
            try (var connection = pool.getConnection(); var statement = connection.prepareStatement(
                    "SELECT version FROM kanban_tasks WHERE id = ?")) {
                statement.setString(1, "00000000-0000-0000-0000-000000000000");
                statement.setQueryTimeout(2);
                try (var ignored = statement.executeQuery()) { return Health.up().build(); }
            } catch (Exception unavailable) { return Health.down().build(); }
        };
    }

    @Bean RootsConfigCustomizer policies() {
        var rejected = Response.text(403, "Access denied");
        return config -> config
                .authorize("read", request -> request.identity()
                        .filter(i -> i.hasRole("VIEWER") || i.hasRole("EDITOR")).isPresent()
                        ? AuthorizationPolicy.allow() : AuthorizationPolicy.deny(rejected))
                .authorize("edit", AuthorizationPolicy.role("EDITOR", rejected))
                .mapException(TaskRepository.Missing.class, (request, failure) -> Response.text(404, "Record unavailable"))
                // URL-encoded Unicode descriptions can expand well beyond their character count.
                .maxRequestBytes(131072);
    }
}
