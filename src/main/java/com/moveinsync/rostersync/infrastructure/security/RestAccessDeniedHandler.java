package com.moveinsync.rostersync.infrastructure.security;

import com.moveinsync.rostersync.api.error.ErrorCode;
import com.moveinsync.rostersync.api.error.GlobalExceptionHandler;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

public final class RestAccessDeniedHandler implements AccessDeniedHandler {
    private final JsonMapper mapper;
    public RestAccessDeniedHandler(JsonMapper mapper) { this.mapper = mapper; }
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) throws IOException {
        response.setStatus(403);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), GlobalExceptionHandler.problem(ErrorCode.FORBIDDEN));
    }
}
