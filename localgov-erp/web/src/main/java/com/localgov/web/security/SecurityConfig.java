package com.localgov.web.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.ForwardedHeaderFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            CsrfCookieFilter csrfCookieFilter,
            SecurityAuditFilter securityAuditFilter,
            TenantContextFilter tenantContextFilter,
            RestAuthenticationEntryPoint restAuthenticationEntryPoint,
            RestAccessDeniedHandler restAccessDeniedHandler,
            AuthenticationProvider authenticationProvider,
            @Value("${app.security.require-ssl:false}") boolean requireSecureChannel
    ) throws Exception {
        if (requireSecureChannel) {
            http.requiresChannel(channel -> channel.anyRequest().requiresSecure());
        }

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers(
                                new AntPathRequestMatcher("/auth/**"),
                                new AntPathRequestMatcher("/actuator/**"),
                                new AntPathRequestMatcher("/v3/api-docs/**"),
                                new AntPathRequestMatcher("/swagger-ui/**"),
                                request -> {
                                    String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
                                    return authHeader != null && authHeader.startsWith("Bearer ");
                                }
                        )
                )
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'"))
                        .referrerPolicy(referrer -> referrer.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                )
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(sessionFixation -> sessionFixation.migrateSession())
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/chilanga/**", "/platform/**", "/status", "/auth/login", "/login/**", "/oauth2/**", "/saml2/**", "/error", "/health", "/actuator/health", "/actuator/info", "/actuator/metrics", "/actuator/prometheus", "/v3/api-docs/**", "/swagger-ui/**", "/meta/schema/**", "/dashboard/**", "/examples/**", "/assets/**", "/privacy/compliance").permitAll()
                        .requestMatchers("/auth/mfa/**").authenticated()
                        .requestMatchers("/security/audit-logs/**").hasAnyRole("ADMIN", "HR")
                        .requestMatchers("/audit/**").hasRole("AUDITOR")
                        .requestMatchers("/privacy/data-requests/**").authenticated()
                        .requestMatchers("/api/salary-scales/**").hasAnyRole("ADMIN", "HR", "PAYROLL")
                        .requestMatchers("/api/employment/process-annual-increment").hasAnyRole("ADMIN", "HR")
                        .requestMatchers("/api/employment/process-batch-increments").hasAnyRole("ADMIN", "HR")
                        .requestMatchers("/api/employment/suspicious-changes").hasAnyRole("ADMIN", "HR")
                        .requestMatchers("/api/employment/audit-history").hasAnyRole("ADMIN", "HR")
                        .requestMatchers("/api/employment/**").hasAnyRole("ADMIN", "HR", "PAYROLL")
                        .requestMatchers("/employee-work-locations/**").hasAnyRole("ADMIN", "HR")
                        .requestMatchers("/work-locations/**").authenticated()
                        .requestMatchers("/employees/**").hasAnyRole("ADMIN", "HR")
                        .requestMatchers("/leaves/**").hasAnyRole("ADMIN", "HR", "HEAD", "MANAGER", "EMPLOYEE")
                        .requestMatchers("/documents/**").permitAll()
                        .requestMatchers("/payroll/me").hasAnyRole("ADMIN", "HR", "PAYROLL", "EMPLOYEE")
                        .requestMatchers("/payroll/**").hasAnyRole("ADMIN", "PAYROLL")
                        .requestMatchers("/reports/**").hasAnyRole("ADMIN", "HR", "HEAD", "MANAGER", "FINANCE", "PAYROLL")
                        .requestMatchers("/salary-advances/**").hasAnyRole("ADMIN", "HR", "HEAD", "MANAGER", "FINANCE", "PAYROLL", "EMPLOYEE")
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(csrfCookieFilter, org.springframework.security.web.csrf.CsrfFilter.class)
                .addFilterAfter(securityAuditFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(tenantContextFilter, SecurityAuditFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider() {
            @Override
            protected void additionalAuthenticationChecks(UserDetails userDetails, UsernamePasswordAuthenticationToken authentication) {
                // DEV MODE: Skip password validation - accept any password
                // TODO: Remove this in production and use: super.additionalAuthenticationChecks(userDetails, authentication);
            }
        };
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder);
        return authProvider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public ForwardedHeaderFilter forwardedHeaderFilter() {
        return new ForwardedHeaderFilter();
    }
}
