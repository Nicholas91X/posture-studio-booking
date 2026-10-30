package com.posturestudio.booking.shared;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The only place where exceptions become HTTP responses, as ProblemDetail (RFC 9457).
 * ResponseEntityExceptionHandler already handles Spring MVC's own exceptions (unreadable JSON,
 * wrong method, wrong content type...); here our exceptions and the validation errors.
 * Never a stack trace, SQL or database message in a response: details go to the log.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    // 400: one entry per invalid field, with the violated constraint (e.g. "Positive"),
    // which the frontend translates into Italian
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = e.getBody();
        problem.setDetail("Invalid request content");
        List<Map<String, String>> errors = e.getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "constraint", String.valueOf(error.getCode())))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(e, problem, headers, status, request);
    }

    @ExceptionHandler(NotAuthenticatedException.class)
    ProblemDetail notAuthenticated(NotAuthenticatedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    // 404 for "missing" and for "someone else's": the same message, nothing to tell them apart
    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail businessRule(BusinessRuleException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("code", e.getCode().name());
        return problem;
    }

    // a constraint of the database: validation and rules should have stopped it, unless two
    // requests raced. The database message names tables and constraints: log only
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail dataConflict(DataIntegrityViolationException e) {
        log.warn("Database constraint violated", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The request conflicts with existing data");
        problem.setProperty("code", "DATA_CONFLICT");
        return problem;
    }

    // anything else is a bug (including IllegalArgumentException from an entity: a validation
    // annotation is missing). Generic answer, full details in the log
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unexpected error", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }
}
