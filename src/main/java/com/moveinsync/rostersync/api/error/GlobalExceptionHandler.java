package com.moveinsync.rostersync.api.error;

import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException exception) {
        return problem(exception.errorCode());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception exception) {
        LOG.error("request.failed exceptionClass={} correlationId={}",
                exception.getClass().getSimpleName(), MDC.get(CorrelationIdFilter.MDC_KEY));
        return problem(ErrorCode.INTERNAL_ERROR);
    }

    public static ProblemDetail problem(ErrorCode code) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(code.httpStatus(), code.title());
        detail.setTitle(code.title());
        detail.setType(URI.create("https://docs.rostersync.local/errors/"
                + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        detail.setProperty("code", code.name());
        detail.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        return detail;
    }
}
