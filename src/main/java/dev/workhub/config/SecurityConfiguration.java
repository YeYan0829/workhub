package dev.workhub.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.workhub.common.*;
import dev.workhub.identity.IdentityRepository;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(IdentityRepository users) {
        return login -> {
            var user =
                    users.findByLogin(login)
                            .orElseThrow(
                                    () -> new UsernameNotFoundException("Invalid credentials"));
            return User.withUsername(user.login())
                    .password(user.passwordHash())
                    .disabled(!user.active())
                    .roles("USER")
                    .build();
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, ObjectMapper mapper, dev.workhub.onboarding.RateLimits rate)
            throws Exception {
        return http.addFilterBefore(
                        new dev.workhub.onboarding.SensitiveEntryFilter(rate),
                        org.springframework.security.web.authentication
                                .UsernamePasswordAuthenticationFilter.class)
                .csrf(csrf -> csrf.ignoringRequestMatchers("/integrations/github/webhook"))
                .authorizeHttpRequests(
                        requests ->
                                requests.requestMatchers(
                                                "/",
                                                "/index.html",
                                                "/app.js",
                                                "/model-settings.js",
                                                "/project-overview.js",
                                                "/style.css",
                                                "/sample.csv",
                                                "/account/register",
                                                "/account/verification",
                                                "/account/forgot",
                                                "/account/verify",
                                                "/account/reset",
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/session/csrf",
                                                "/session/login",
                                                "/invitations/preview",
                                                "/integrations/github/webhook")
                                        .permitAll()
                                        .requestMatchers(
                                                "/projects/**",
                                                "/agent/conversations/**",
                                                "/workspaces",
                                                "/workspaces/**",
                                                "/invitations/**",
                                                "/account/**",
                                                "/me/**",
                                                "/session/me")
                                        .authenticated()
                                        .anyRequest()
                                        .denyAll())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, error) -> {
                                                    response.setStatus(403);
                                                    response.setContentType("application/json");
                                                    mapper.writeValue(
                                                            response.getOutputStream(),
                                                            ApiError.from(
                                                                    ApiException.forbidden()));
                                                })
                                        .accessDeniedHandler(
                                                (request, response, error) -> {
                                                    response.setStatus(403);
                                                    response.setContentType("application/json");
                                                    mapper.writeValue(
                                                            response.getOutputStream(),
                                                            ApiError.from(
                                                                    ApiException.forbidden()));
                                                }))
                .formLogin(
                        login ->
                                login.loginProcessingUrl("/session/login")
                                        .successHandler(
                                                (request, response, auth) ->
                                                        response.setStatus(204))
                                        .failureHandler(
                                                (request, response, error) -> {
                                                    response.setStatus(401);
                                                    response.setContentType("application/json");
                                                    mapper.writeValue(
                                                            response.getOutputStream(),
                                                            ApiError.from(
                                                                    new ApiException(
                                                                            401,
                                                                            "INVALID_CREDENTIALS",
                                                                            "登录名或密码错误，请重新输入。")));
                                                }))
                .logout(
                        logout ->
                                logout.logoutUrl("/session/logout")
                                        .logoutSuccessHandler(
                                                (request, response, auth) ->
                                                        response.setStatus(204)))
                .build(); // CSRF remains enabled, including login/logout; session is persisted by
        // Spring Session JDBC.
    }
}
