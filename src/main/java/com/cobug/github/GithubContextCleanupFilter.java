package com.cobug.github;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class GithubContextCleanupFilter extends OncePerRequestFilter {
    private final GithubService githubService;
    public GithubContextCleanupFilter(GithubService githubService){this.githubService=githubService;}
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try { chain.doFilter(request,response); } finally { githubService.clearRequestContext(); }
    }
}
