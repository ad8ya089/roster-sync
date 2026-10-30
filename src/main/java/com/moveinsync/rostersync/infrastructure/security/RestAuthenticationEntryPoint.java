package com.moveinsync.rostersync.infrastructure.security;

import com.moveinsync.rostersync.api.error.ErrorCode;
import com.moveinsync.rostersync.api.error.GlobalExceptionHandler;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

public final class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final JsonMapper mapper;
    public RestAuthenticationEntryPoint(JsonMapper mapper) { this.mapper = mapper; }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        ErrorCode code = expired(exception) ? ErrorCode.TOKEN_EXPIRED : ErrorCode.UNAUTHENTICATED;
        response.setStatus(code.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("WWW-Authenticate", "Bearer");
        mapper.writeValue(response.getOutputStream(), GlobalExceptionHandler.problem(code));
    }

    private static boolean expired(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof JwtValidationException validation && validation.getErrors().stream()
                    .anyMatch(error -> JwtClaimsValidator.EXPIRED.equals(error.getErrorCode()))) return true;
        }
        return false;
    }
}
