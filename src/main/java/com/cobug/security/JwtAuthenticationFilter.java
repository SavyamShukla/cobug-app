package com.cobug.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            CustomUserDetailsService userDetailsService
    ) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authorizationHeader =
                request.getHeader("Authorization");

        log.debug("JWT request {} {}", request.getMethod(), request.getRequestURI());

        if (authorizationHeader == null ||
                !authorizationHeader.startsWith("Bearer ")) {

            log.debug("No Bearer token found for {} {}", request.getMethod(), request.getRequestURI());

            filterChain.doFilter(request, response);
            return;
        }

        String token =
                authorizationHeader.substring(7).trim();

        if (token.isEmpty()) {

            log.debug("Bearer token is empty.");

            filterChain.doFilter(request, response);
            return;
        }

        try {

            String email =
                    jwtService.extractEmail(token);

            log.debug("JWT subject resolved for request.");

            if (email != null &&
                    SecurityContextHolder
                            .getContext()
                            .getAuthentication() == null) {

                UserDetails userDetails =
                        userDetailsService
                                .loadUserByUsername(email);

                log.debug("JWT user loaded.");

                boolean valid =
                        jwtService.isValid(
                                token,
                                userDetails
                        );

                log.debug("JWT token valid: {}", valid);

                if (valid) {

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,
                                    userDetails.getAuthorities()
                            );

                    authentication.setDetails(
                            new WebAuthenticationDetailsSource()
                                    .buildDetails(request)
                    );

                    SecurityContextHolder
                            .getContext()
                            .setAuthentication(authentication);

                    log.debug("JWT authentication succeeded.");
                } else {

                    log.debug("JWT authentication failed: invalid token.");
                }
            }

        } catch (Exception e) {

            /*
             * IMPORTANT:
             * The old code silently ignored this exception.
             * That made every JWT problem look like a mysterious
             * 403 Forbidden.
             */

            log.warn("JWT authentication error: {}", e.getClass().getSimpleName());

            log.debug("JWT authentication exception message: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}