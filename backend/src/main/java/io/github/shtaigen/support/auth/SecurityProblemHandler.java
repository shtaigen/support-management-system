package io.github.shtaigen.support.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Turns security failures into the same Problem Details as every other API error (NFR-03) by
 * handing them to the MVC exception resolvers, which end up in {@code ApiExceptionHandler}.
 * <p>
 * 401 bodies are identical for a missing, expired, forged or malformed token, and
 * {@code WWW-Authenticate} carries no {@code error_description}: the response never says why a
 * token was rejected. The security exception is not passed on or logged, so tokens never reach the
 * logs (NFR-02).
 */
@Component
class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    SecurityProblemHandler(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // RFC 6750 section 3.1: no error code when the request had no token at all.
        String challenge = authException instanceof OAuth2AuthenticationException
                ? "Bearer error=\"invalid_token\""
                : "Bearer";
        ErrorResponseException error = error(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        error.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge);
        if (resolver.resolveException(request, response, null, error) == null) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
            response.sendError(HttpStatus.UNAUTHORIZED.value());
        }
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        ErrorResponseException error = error(HttpStatus.FORBIDDEN, "Access is denied.");
        if (resolver.resolveException(request, response, null, error) == null) {
            response.sendError(HttpStatus.FORBIDDEN.value());
        }
    }

    private static ErrorResponseException error(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        return new ErrorResponseException(status, problem, null);
    }
}
