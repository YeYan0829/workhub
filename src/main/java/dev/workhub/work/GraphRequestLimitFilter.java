package dev.workhub.work;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Enforce the query-body budget before JSON materialization, including chunked uploads. */
@Component
@Order(-90)
public class GraphRequestLimitFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest r) {
        return !r.getMethod().equals("POST")
                || !r.getRequestURI().matches("/projects/[0-9a-fA-F-]{36}/graph/(view|sync)");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(32769);
        if (body.length > 32768) {
            response.setStatus(413);
            response.setContentType("application/json");
            response.getWriter()
                    .write("{\"code\":\"GRAPH_REQUEST_LIMIT\",\"message\":\"图范围请求超过32KiB\"}");
            return;
        }
        chain.doFilter(
                new HttpServletRequestWrapper(request) {
                    @Override
                    public ServletInputStream getInputStream() {
                        var input = new ByteArrayInputStream(body);
                        return new ServletInputStream() {
                            public int read() {
                                return input.read();
                            }

                            public boolean isFinished() {
                                return input.available() == 0;
                            }

                            public boolean isReady() {
                                return true;
                            }

                            public void setReadListener(ReadListener listener) {
                                throw new UnsupportedOperationException("Synchronous JSON body");
                            }
                        };
                    }

                    @Override
                    public BufferedReader getReader() {
                        return new BufferedReader(
                                new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
                    }
                },
                response);
    }
}
