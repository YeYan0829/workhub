package dev.workhub.work;

import jakarta.servlet.*;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

/**
 * A disconnected SSE response is already committed. Finish its container error dispatch quietly;
 * the connection's AsyncListener has released the subscription. Initial requests remain secured.
 */
@Configuration
public class GraphStreamErrorDispatch {
    @Bean
    FilterRegistrationBean<Filter> overviewStreamErrors() {
        Filter filter =
                (request, response, chain) -> {
                    Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
                    if (response.isCommitted()
                            && original instanceof String uri
                            && uri.matches("/projects/[0-9a-fA-F-]{36}/graph/events")) return;
                    chain.doFilter(request, response);
                };
        var registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(DispatcherType.ERROR);
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }
}
