package com.moveinsync.rostersync.api.error;

import java.net.URI;
import java.util.Locale;
import java.util.Comparator;
import java.util.List;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException exception) {
        if (exception instanceof RequestValidationException validation) {
            var detail = problem(validation.errorCode()); detail.setProperty("errors", validation.errors()); return detail;
        }
        return problem(exception.errorCode());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail forbidden() { return problem(ErrorCode.FORBIDDEN); }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ProblemDetail notFound() { return problem(ErrorCode.NOT_FOUND); }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail methodNotAllowed() { return problem(ErrorCode.METHOD_NOT_ALLOWED); }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail unsupportedMediaType() { return problem(ErrorCode.UNSUPPORTED_MEDIA_TYPE); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail malformedJson() { return problem(ErrorCode.MALFORMED_JSON); }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail tooLarge() { return problem(ErrorCode.PAYLOAD_TOO_LARGE); }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail typeMismatch(MethodArgumentTypeMismatchException exception) {
        return invalid(List.of(new FieldErrorDto(exception.getName(), "INVALID_FORMAT")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail missingParameter(MissingServletRequestParameterException exception) {
        return invalid(List.of(new FieldErrorDto(exception.getParameterName(), "MISSING_REQUIRED_FIELD")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException exception) {
        return invalid(exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorDto(error.getField(), validationCode(error.getCode()))).toList());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail methodValidation(HandlerMethodValidationException exception) {
        return invalid(exception.getParameterValidationResults().stream().flatMap(result -> result.getResolvableErrors().stream()
                .map(error -> new FieldErrorDto(result.getMethodParameter().getParameterName(),
                        validationCode(error.getCodes() == null ? null : error.getCodes()[error.getCodes().length - 1])))).toList());
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ProblemDetail dependencyUnavailable() { return problem(ErrorCode.DEPENDENCY_UNAVAILABLE); }

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail databaseFailure(DataAccessException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            // Use PostgreSQL's structured constraint metadata, never its message or rejected values.
            if (cause instanceof PSQLException postgres && "23505".equals(postgres.getSQLState())
                    && postgres.getServerErrorMessage() != null
                    && "office_tenant_id_code_key".equals(postgres.getServerErrorMessage().getConstraint())) {
                return problem(ErrorCode.OFFICE_CODE_EXISTS);
            }
        }
        return handleUnexpectedException(exception);
    }

    private static String validationCode(String constraint) {
        if ("NotBlank".equals(constraint) || "NotNull".equals(constraint)) return "MISSING_REQUIRED_FIELD";
        if ("Size".equals(constraint)) return "TOO_LONG";
        return "INVALID_FORMAT";
    }

    private static ProblemDetail invalid(List<FieldErrorDto> errors) {
        ProblemDetail detail = problem(ErrorCode.VALIDATION_FAILED);
        var sorted = errors.stream().distinct().sorted(Comparator.comparing(FieldErrorDto::field)
                .thenComparing(FieldErrorDto::code)).toList();
        detail.setDetail(sorted.size() + " field(s) invalid");
        detail.setProperty("errors", sorted);
        return detail;
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
        // Spring otherwise copies the request URI here; malformed paths may contain personal data.
        detail.setInstance(URI.create("/api/v1"));
        detail.setType(URI.create("https://docs.rostersync.local/errors/"
                + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        detail.setProperty("code", code.name());
        detail.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        return detail;
    }
}
