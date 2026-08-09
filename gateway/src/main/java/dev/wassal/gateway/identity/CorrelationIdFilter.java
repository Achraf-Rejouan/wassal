package dev.wassal.gateway.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates and propagates {@code X-Correlation-Id} (NFR-010, security amendment A-1).
 *
 * <p>The header is client-supplied and ends up in logs, so it is validated as a strict UUID and
 * <em>never</em> logged raw. CRLF in this header forges log entries — and in a system whose output
 * <em>is</em> evidence, forging log entries means fabricating evidence, which is the worst outcome
 * the threat model contemplates.
 */
@Component
@Order(1)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String supplied = request.getHeader(HEADER);
        UUID correlationId;

        if (supplied == null || supplied.isBlank()) {
            correlationId = UUID.randomUUID();
        } else {
            try {
                correlationId = UUID.fromString(supplied);
            } catch (IllegalArgumentException e) {
                // Rejected rather than sanitised: silently accepting a malformed id would let a
                // caller choose what appears in the evidence trail.
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json");
                response.getWriter()
                        .write(
                                "{\"code\":\"VALIDATION_FAILED\",\"message\":"
                                        + "\"X-Correlation-Id must be a UUID\"}");
                return;
            }
        }

        MDC.put(MDC_KEY, correlationId.toString());
        response.setHeader(HEADER, correlationId.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
