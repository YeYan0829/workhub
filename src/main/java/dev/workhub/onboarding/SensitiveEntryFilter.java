package dev.workhub.onboarding;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class SensitiveEntryFilter extends OncePerRequestFilter {
    private final RateLimits rate;

    public SensitiveEntryFilter(RateLimits rate) {
        this.rate = rate;
    }

    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (req.getMethod().equals("POST")
                && (req.getRequestURI().startsWith("/account/")
                        || req.getRequestURI().equals("/session/login"))) {
            boolean allowed = rate.take("ip/" + req.getRemoteAddr(), 60);
            if (req.getRequestURI().equals("/session/login"))
                allowed = rate.take("login/" + req.getParameter("username"), 15) && allowed;
            if (!allowed) {
                res.setStatus(429);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter()
                        .write("{\"code\":\"RATE_LIMITED\",\"message\":\"请求过于频繁，请15分钟后重试\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
