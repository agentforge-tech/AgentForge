package cloud.changlu.agentforge.service.web;

import cloud.changlu.agentforge.service.env.EnvironmentContext;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;

/**
 * Forwards history-mode SPA routes to {@code index.html}.
 *
 * <p>Only top-level GET navigations are forwarded. API, upload, error and asset requests keep their
 * normal handling, and the filter stays inactive when no built frontend is present.
 *
 * @author changlu
 * @since 2026-10-29
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class SpaForwardFilter extends OncePerRequestFilter {

    private final EnvironmentContext environmentContext;

    public SpaForwardFilter(EnvironmentContext environmentContext) {
        this.environmentContext = environmentContext;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!shouldForward(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        request.getRequestDispatcher("/index.html").forward(request, response);
    }

    private boolean shouldForward(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String accept = request.getHeader("Accept");
        if (accept == null || !accept.contains("text/html")) {
            return false;
        }
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (path.isEmpty()) {
            path = "/";
        }
        if (path.startsWith("/api/")
                || path.startsWith("/upload/")
                || "/error".equals(path)
                || "/index.html".equals(path)) {
            return false;
        }
        int lastSlash = path.lastIndexOf('/');
        String lastSegment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        if (lastSegment.contains(".")) {
            return false;
        }
        return frontendExists();
    }

    private boolean frontendExists() {
        String location = environmentContext.getWebStaticLocation();
        if (!StringUtils.hasText(location) || !location.startsWith("file:")) {
            return false;
        }
        return new File(location.substring("file:".length()), "index.html").isFile();
    }
}
