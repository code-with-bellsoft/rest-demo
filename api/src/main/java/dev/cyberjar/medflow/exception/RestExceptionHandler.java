package dev.cyberjar.medflow.exception;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class RestExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @Nullable ResponseEntity<Object> handleNotFound(NotFoundException ex, WebRequest request) {
        return handleDomainError(ex, HttpStatus.NOT_FOUND, request);
    }

    @ExceptionHandler(SlotConflictException.class)
    @Nullable ResponseEntity<Object> handleConflict(SlotConflictException ex, WebRequest request) {
        return handleDomainError(ex, HttpStatus.CONFLICT, request);
    }

    private @Nullable ResponseEntity<Object> handleDomainError(Exception ex, HttpStatus status, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        return handleExceptionInternal(ex, problem, HttpHeaders.EMPTY, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> validationError(error, "body"))
                .toList();
        return handleExceptionInternal(ex, validationProblem(status, errors), headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex.isForReturnValue()) {
            return super.handleHandlerMethodValidationException(ex, headers, status, request);
        }

        var errors = new ArrayList<Map<String, String>>();
        for (var result : ex.getParameterValidationResults()) {
            String name = result.getMethodParameter().getParameterName();
            for (var error : result.getResolvableErrors()) {
                errors.add(validationError(error, name == null ? "request" : name));
            }
        }
        for (var error : ex.getCrossParameterValidationResults()) {
            errors.add(validationError(error, "request"));
        }
        return handleExceptionInternal(ex, validationProblem(status, errors), headers, status, request);
    }

    private Map<String, String> validationError(MessageSourceResolvable error, String fallbackField) {
        String field = error instanceof FieldError fieldError ? fieldError.getField() : fallbackField;
        String message = error.getDefaultMessage();
        return Map.of("field", field, "message", message == null ? "Invalid value" : message);
    }

    private ProblemDetail validationProblem(HttpStatusCode status, List<Map<String, String>> errors) {
        var problem = ProblemDetail.forStatusAndDetail(status, "Request validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, "A request parameter has an invalid format");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @ExceptionHandler(exception = HttpMediaTypeNotAcceptableException.class,
            produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    @Nullable ResponseEntity<Object> handleNegotiatedNotAcceptable(
            HttpMediaTypeNotAcceptableException ex, WebRequest request) {
        return super.handleHttpMediaTypeNotAcceptable(ex, ex.getHeaders(), ex.getStatusCode(), request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Spring otherwise falls back to a problem representation even when Accept excludes it.
        return createResponseEntity(null, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    @Nullable ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) throws Exception {
        if (ex instanceof ErrorResponse response) {
            return handleExceptionInternal(ex, null, response.getHeaders(), response.getStatusCode(), request);
        }
        if (AnnotatedElementUtils.hasAnnotation(ex.getClass(), ResponseStatus.class)) {
            throw ex;
        }
        return handleExceptionInternal(ex, null, HttpHeaders.EMPTY, HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            // Exception messages can contain SQL and bound values.
            var origin = Arrays.stream(ex.getStackTrace()).findFirst().map(StackTraceElement::toString)
                    .orElse("unknown");
            logger.error("Request failed with HTTP " + status.value() + " (" + ex.getClass().getName()
                    + " at " + origin + ")");
            body = ProblemDetail.forStatusAndDetail(status, "The server could not complete the request");
        }
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getType() == null) {
            problem.setType(URI.create("about:blank"));
        }
        return super.createResponseEntity(body, headers, status, request);
    }
}
