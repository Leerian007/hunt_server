package com.guxx.ray.ai.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guxx.ray.ai.common.Result;
import com.guxx.ray.ai.common.SteamHttpClientFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.client.RestClient;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class AuthConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RestClient steamClient(@Value("${steam.proxy-url:}") String proxyUrl) {
        var client = SteamHttpClientFactory.create(proxyUrl);
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${web.return-url}") String returnUrl) {
        URI uri = URI.create(returnUrl);
        String configuredOrigin = uri.getScheme() + "://" + uri.getRawAuthority();

        CorsConfiguration cors = new CorsConfiguration();

        // 1. 允许配置的线上/生产域名，同时允许本地任意端口的 localhost 和 127.0.0.1（适配 Flutter Web 的随机调试端口）
        cors.setAllowedOriginPatterns(List.of(
                configuredOrigin,
                "http://localhost:*",
                "http://127.0.0.1:*"
        ));

        // 2. 允许所有标准 HTTP 方法
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // 3. 关键：允许所有 Header（Flutter 底层网络库常带自定义/默认额外头）
        cors.setAllowedHeaders(List.of("*"));

        // 4. 支持凭据（Cookie / Token）
        cors.setAllowCredentials(true);

        // 5. 缓存预检结果（秒），避免每次接口调用都发 OPTIONS 请求
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 建议对所有路径生效，防止其它 API 遗漏
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwt, ObjectMapper mapper) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults()) // 会自动使用上面声明的 corsConfigurationSource Bean
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 关键点 1：必须优先放行所有 OPTIONS 预检请求
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 业务公开接口白名单
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/steam/login-url", "/api/v1/auth/steam/callback").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setStatus(401);
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            mapper.writeValue(response.getOutputStream(), Result.error(401, "Bearer token required"));
                        })
                        .accessDeniedHandler((request, response, ex) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            mapper.writeValue(response.getOutputStream(), Result.error(403, "Access denied"));
                        }))
                .addFilterBefore(new JwtAuthFilter(jwt, mapper), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
