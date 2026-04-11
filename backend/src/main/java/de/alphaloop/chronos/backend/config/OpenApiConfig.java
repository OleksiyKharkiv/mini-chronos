package de.alphaloop.chronos.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenApiConfig — конфигурация Swagger UI и OpenAPI 3 спецификации.
 * <p>
 * springdoc-openapi автоматически:
 *   1. Сканирует все @RestController классы
 *   2. Генерирует OpenAPI 3 спецификацию (JSON/YAML)
 *   3. Поднимает Swagger UI
 * <p>
 * URLS после запуска:
 *   Swagger UI:        http://localhost:8080/swagger-ui.html
 *   OpenAPI JSON:      http://localhost:8080/v3/api-docs
 *   OpenAPI YAML:      http://localhost:8080/v3/api-docs.yaml
 * <p>
 * Живая документация API — можно прямо здесь тестировать эндпоинты."
 * <p>
 * СХЕМА БЕЗОПАСНОСТИ (JWT Bearer):
 * Добавляем SecurityScheme чтобы в Swagger UI появилась кнопка "Authorize".
 * После клика: вводишь токен → все запросы из Swagger идут с заголовком
 * Authorization: Bearer <твой_токен>.
 * Без этого: все запросы возвращают 401 (когда добавим Spring Security).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI miniChronosOpenAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("Local development server"),
                        new Server()
                                .url("https://mini-chronos.alphaloop.de")
                                .description("Production (future)")
                ))
                // Регистрируем схему безопасности JWT
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", jwtSecurityScheme())
                )
                // Применяем схему ко ВСЕМ эндпоинтам глобально.
                // Альтернатива: @SecurityRequirement на каждом контроллере/методе.
                // Глобально проще для MVP — потом уберём с публичных эндпоинтов.
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    private Info apiInfo() {
        return new Info()
                .title("mini-chronos API")
                .description("""
                        REST API for mini-chronos — a simplified ERP module
                        inspired by Alpha Loop's Chronos system.
                                               \s
                        Covers: Customer management, Project tracking,
                        Equipment rental with availability checking,
                        Order lifecycle with optimistic locking.
                                               \s
                        Built as a practical preparation for internship at Alpha Loop GmbH.
                       \s""")
                .version("0.1.0-MVP")
                .contact(new Contact()
                        .name("mini-chronos dev")
                        .email("dev@mini-chronos.de")
                );
    }

    private SecurityScheme jwtSecurityScheme() {
        return new SecurityScheme()
                // type: HTTP → используем стандартную HTTP аутентификацию
                .type(SecurityScheme.Type.HTTP)
                // scheme: bearer → Authorization: Bearer <token>
                .scheme("bearer")
                // bearerFormat: JWT → подсказка для UI (не влияет на поведение)
                .bearerFormat("JWT")
                .description("Enter JWT token (without 'Bearer ' prefix)");
    }
}