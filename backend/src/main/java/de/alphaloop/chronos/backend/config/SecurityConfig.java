package de.alphaloop.chronos.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * SecurityConfig — конфигурация Spring Security для mini-chronos.
 * ─────────────────────────────────────────────────────────────────────
 * Архитектурные решения:
 *
 * 1. OAuth2 Resource Server (JWT) вместо BasicAuth или Session:
 *    - REST API должен быть stateless — никаких сессий на сервере
 *    - JWT содержит все claims (роли) — не нужен DB-запрос на каждый запрос
 *    - Для Angular SPA: токен хранится в памяти, передаётся в заголовке
 *
 * 2. CORS: Angular (localhost:4200) — другой origin чем бэкенд (localhost:8080).
 *    Без CORS: браузер блокирует ВСЕ запросы от Angular к Spring.
 *    CORS настраивается на уровне Spring — NGINX в dev не используется.
 *
 * 3. Swagger whitelist: springdoc не защищён токеном — иначе нельзя открыть
 *    документацию до получения токена (курица-яйцо).
 *    Только /swagger-ui/**, /v3/api-docs/** — минимальный whitelist.
 *
 * 4. CSRF отключён:
 *    CSRF защита нужна для форм с cookie-сессиями.
 *    Для stateless JWT API: не нужна (токен в Authorization header,
 *    не в cookie → браузер не отправит его автоматически на другой сайт).
 *
 * 5. @EnableMethodSecurity: включает @PreAuthorize на уровне методов.
 *    Пример в контроллере: @PreAuthorize("hasRole('ADMIN')")
 *    Без этой аннотации @PreAuthorize работать НЕ будет!
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true)
public class SecurityConfig {

    /**
     * Публичные пути — не требуют JWT токена.
     * Вынесены в константы для читаемости и переиспользования в тестах.
     */
    private static final String[] PUBLIC_POST_PATHS = {
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh"
    };

    private static final String[] SWAGGER_PATHS = {
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml"
    };

    // ── Password Encoder ────────────────────────────────────────────────────

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt: адаптивная хэш-функция.
        // Strength 12 (default): 2^12 = 4096 итерации хэширования.
        // Медленный намеренно — усложняет брутфорс.
        return new BCryptPasswordEncoder();
    }

    // ── JWT Decoder ─────────────────────────────────────────────────────────

    /**
     * NimbusJwtDecoder: валидирует входящие JWT токены.
     * HS256 (HMAC-SHA256) — симметричный алгоритм, один секрет для подписи и проверки.
     * Для production: рассмотреть RS256 (асимметричный) — публичный ключ можно раздать.
     *
     * Секрет берётся из application.yml: app.jwt.secret
     * НИКОГДА не хардкоди секрет в коде — только через переменные окружения!
     */
    @Bean
    public JwtDecoder jwtDecoder(@Value("${app.jwt.secret}") String secret) {
        // Требование: ключ минимум 32 байта для HS256 (256 бит).
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }

    // ── CORS Configuration ──────────────────────────────────────────────────

    /**
     * CORS (Cross-Origin Resource Sharing) — разрешаем Angular обращаться к нашему API.
     *
     * БЕЗ CORS: браузер видит что Angular (localhost:4200) запрашивает
     * данные у другого origin (localhost:8080) и блокирует ответ.
     * Это политика Same-Origin Policy — защита браузера.
     *
     * CORS — это ответ сервера браузеру: "да, я доверяю этому origin".
     * Браузер читает заголовок Access-Control-Allow-Origin и пропускает ответ.
     *
     * allowCredentials=true: нужен если Angular отправляет cookies (не наш случай —
     * у нас JWT в header). Оставляем false для безопасности.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:4200}") String allowedOrigins
    ) {
        CorsConfiguration config = new CorsConfiguration();

        // Разрешённые origins из конфига — не хардкодим в коде!
        // dev: http://localhost:4200 (Angular dev server)
        // prod: https://chronos.alphaloop.de (реальный домен)
        config.setAllowedOrigins(List.of(allowedOrigins.split(",")));

        // Разрешённые HTTP методы (только те, что используем в API)
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // OPTIONS: preflight запрос — браузер спрашивает "можно мне?" перед реальным запросом.
        // Authorization: нужен для JWT токена в заголовке.
        // Content-Type: нужен для JSON body в POST/PUT запросах.
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));

        // Сколько секунд браузер кэширует preflight ответ (не делает OPTIONS каждый раз).
        // 3600 = 1 час — разумно для dev, в prod можно больше.
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    // ── Security Filter Chain ────────────────────────────────────────────────

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                // CSRF отключён — см. пояснение в javadoc класса
                .csrf(AbstractHttpConfigurer::disable)

                // CORS: подключаем наш CorsConfigurationSource bean выше.
                // Customizer.withDefaults() → Spring ищет CorsConfigurationSource bean.
                .cors(Customizer.withDefaults())

                // Stateless: Spring Security НЕ создаёт HTTP сессию.
                // JWT токен в каждом запросе — авторизация без состояния на сервере.
                .sessionManagement(s ->
                        s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                .authorizeHttpRequests(auth -> auth
                        // Swagger UI и OpenAPI JSON — публичные (для разработки)
                        .requestMatchers(SWAGGER_PATHS).permitAll()

                        // Auth эндпоинты — публичные (без токена нельзя получить токен!)
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_PATHS).permitAll()

                        // Actuator health — публичный (для Docker healthcheck и мониторинга)
                        .requestMatchers("/actuator/health").permitAll()

                        // Всё остальное — только с валидным JWT
                        .anyRequest().authenticated()
                )

                // JWT Resource Server: фильтр извлекает JWT из "Authorization: Bearer <token>",
                // валидирует через JwtDecoder bean, преобразует в Authentication.
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt ->
                                jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                );

        return http.build();
    }

    // ── JWT → Spring Security Authorities Converter ─────────────────────────

    /**
     * Преобразует JWT claims в Spring Security GrantedAuthority.
     *
     * Проблема: JWT содержит роли как {"roles": ["ADMIN", "SALES"]},
     * Spring Security ожидает GrantedAuthority с префиксом "ROLE_".
     *
     * Решение: конвертер, который читает claim "roles" и добавляет "ROLE_".
     * hasRole('ADMIN') → Spring ищет "ROLE_ADMIN" в authorities.
     * hasAuthority('ADMIN') → ищет "ADMIN" без префикса.
     *
     * Выносим в отдельный метод (не лямбду в FilterChain) — чище и тестируемо.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();

        JwtGrantedAuthoritiesConverter defaultScopesConverter =
                new JwtGrantedAuthoritiesConverter();

        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            // 1. Стандартные scope/scp claims → SCOPE_read, SCOPE_write
            Collection<org.springframework.security.core.GrantedAuthority> authorities =
                    new ArrayList<>(defaultScopesConverter.convert(jwt));

            // 2. Claim "role" (одиночная строка) → ROLE_ADMIN
            Object roleClaim = jwt.getClaims().get("role");
            if (roleClaim instanceof String r && !r.isBlank()) {
                authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_" + r.trim()
                ));
            }

            // 3. Claim "roles" (список) → ROLE_ADMIN, ROLE_SALES, ...
            Object rolesClaim = jwt.getClaims().get("roles");
            if (rolesClaim instanceof Iterable<?> roles) {
                roles.forEach(it -> {
                    if (it != null) {
                        authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_" + it.toString().trim()
                        ));
                    }
                });
            }

            return authorities;
        });

        return converter;
    }
}