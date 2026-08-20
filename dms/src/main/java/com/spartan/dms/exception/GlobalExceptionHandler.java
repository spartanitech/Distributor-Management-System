package com.spartan.dms.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            AuthenticationException ex,
            HttpServletRequest request) {

        // Covers BadCredentialsException (wrong password) and
        // UsernameNotFoundException (account doesn't exist) — both are thrown
        // by authenticationManager.authenticate() inside AuthService.login().
        // Without this handler they fell through to handleException() below
        // and came back as an opaque 500 instead of a real "login failed".
        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error("Unauthorized")
                .message("Invalid username or password")
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(
            ResourceNotFoundException ex,
            HttpServletRequest request) {

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.NOT_FOUND.value())
                .error("Not Found")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(
            BadRequestException ex,
            HttpServletRequest request) {

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * Bean Validation failures (@NotBlank / @NotNull / etc on ProductRequest
     * and friends, triggered by @Valid in the controller). Returns a clean
     * 400 listing exactly which fields failed, instead of letting a bad
     * payload fall through to the persistence layer.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Validation Failed")
                .message(message.isBlank() ? "Invalid request payload" : message)
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * This is the handler that turns the raw Hibernate
     * StaleObjectStateException ("Row was updated or deleted by another
     * transaction (or unsaved-value mapping was incorrect)") — wrapped by
     * Spring Data as ObjectOptimisticLockingFailureException — into a clean,
     * user-facing message instead of leaking the internal exception text to
     * the browser, which is what the screenshot in the bug report showed.
     *
     * With the root-cause fix in ProductService/ProductMapper this should
     * no longer fire for creates. It's kept here as a safety net for
     * legitimate concurrent-edit conflicts on update/delete (e.g. two users
     * editing/deleting the same product at the same time).
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLocking(
            ObjectOptimisticLockingFailureException ex,
            HttpServletRequest request) {

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error("Conflict")
                .message("This record was changed or removed by someone else. Please refresh and try again.")
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex,
            HttpServletRequest request) {

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error("Conflict")
                .message("This record can't be saved or deleted because it conflicts with — or is still referenced by — another record.")
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(
            DuplicateResourceException ex,
            HttpServletRequest request) {

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.CONFLICT.value())
                .error("Conflict")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    /**
     * FileUploadUtil / PaymentProofService throw this for invalid file
     * type, invalid size, or an underlying disk I/O failure during a proof
     * (or other file) upload. Returns a clean 400/422 instead of a raw
     * IOException stack trace.
     */
    @ExceptionHandler(FileStorageException.class)
    public ResponseEntity<ErrorResponse> handleFileStorage(
            FileStorageException ex,
            HttpServletRequest request) {

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("File Upload Failed")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * Thrown by Spring itself when an uploaded file exceeds
     * spring.servlet.multipart.max-file-size / max-request-size, before
     * the request even reaches the controller.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(
            MaxUploadSizeExceededException ex,
            HttpServletRequest request) {

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.PAYLOAD_TOO_LARGE.value())
                .error("File Too Large")
                .message("The uploaded file exceeds the maximum allowed size.")
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.PAYLOAD_TOO_LARGE);
    }

    /**
     * Thrown by @PreAuthorize when an authenticated user doesn't have the
     * required role (e.g. a DISTRIBUTOR calling an ADMIN-only endpoint).
     * Without this handler it fell through to handleException() below and
     * came back as a 500 with message "Access Denied" instead of a real
     * 403 — which meant the frontend's err.status === 403 checks (used to
     * redirect cleanly on permission issues) never fired.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex,
            HttpServletRequest request) {

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error("Forbidden")
                .message("You don't have permission to access this resource.")
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.FORBIDDEN);
    }

    /**
     * BUG-H16 fix: ForbiddenException is the actual mechanism
     * SecurityUtils.assertDistributorAccess()/assertSuperStockistAccess()/
     * assertAdmin() (and several services directly) throw to enforce every
     * cross-tenant/IDOR protection in this codebase — but it had no
     * dedicated handler, so it fell through to the generic
     * handleException(Exception.class) below and came back as a 500
     * Internal Server Error instead of a 403 Forbidden. The request was
     * always still correctly blocked (no data leaked), but the wrong
     * status code broke any frontend logic branching on err.status === 403
     * (the same class of bug already fixed for @PreAuthorize denials by
     * handleAccessDenied() above) and polluted error logs/alerting with
     * routine, expected authorization denials logged as unclassified
     * application errors. IDOR enforcement itself is unchanged — this only
     * fixes how its rejection is reported to the client.
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(
            ForbiddenException ex,
            HttpServletRequest request) {

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error("Forbidden")
                .message(ex.getMessage() != null ? ex.getMessage() : "You don't have permission to access this resource.")
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.FORBIDDEN);
    }

    /**
     * Bean Validation failures on @RequestParam/@PathVariable (methods
     * annotated with @Validated rather than a @Valid @RequestBody DTO).
     * Previously fell through to handleException() below and came back as
     * a raw-message 500 instead of a clean 400.
     */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            jakarta.validation.ConstraintViolationException ex,
            HttpServletRequest request) {

        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Validation Failed")
                .message(message.isBlank() ? "Invalid request parameters" : message)
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * Malformed/unparseable JSON request body (missing quote, trailing
     * comma, wrong type for a field, empty body where one was required).
     * Previously surfaced as a 500 with Jackson's internal parser message.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message("The request body is missing or not valid JSON.")
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * A required @RequestParam was omitted entirely (e.g. calling an
     * endpoint that needs ?keyword= with no query string at all).
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException ex,
            HttpServletRequest request) {

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message("Missing required parameter: " + ex.getParameterName())
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * A @RequestParam/@PathVariable was the wrong type — most commonly an
     * invalid enum string (e.g. ?status=BOGUS against ProductRequestStatus)
     * or a non-numeric id in a path variable expecting Long. Previously a
     * raw 500 with Spring's internal conversion-failure message.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {

        String expected = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "a different type";
        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Bad Request")
                .message("Invalid value for '" + ex.getName() + "' — expected " + expected + ".")
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * No controller mapping matches the requested URL/method at all (a
     * genuinely unknown route, or right route/wrong HTTP verb). Requires
     * spring.mvc.throw-exception-if-no-handler-found=true and
     * spring.web.resources.add-mappings=false (set in application.properties)
     * for this to fire instead of Spring's default static-resource 404.
     */
    @ExceptionHandler(org.springframework.web.servlet.NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoHandlerFound(
            org.springframework.web.servlet.NoHandlerFoundException ex,
            HttpServletRequest request) {

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.NOT_FOUND.value())
                .error("Not Found")
                .message("No such API endpoint: " + ex.getHttpMethod() + " " + ex.getRequestURL())
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    /**
     * Catch-all for anything not handled above. Two real bugs fixed here:
     *   1. Nothing was ever logged server-side for an unclassified 500 --
     *      the only trace of a real bug was whatever the client happened
     *      to report back, with no stack trace to actually debug it.
     *   2. ex.getMessage() was returned verbatim to the client. For an
     *      unclassified exception that can be a raw NPE/SQL/file-path
     *      message exposing internal class names, table/column names, or
     *      file-system paths -- information disclosure with no benefit to
     *      a legitimate caller, who can't act on it anyway.
     * The client now gets a safe, generic message; the real detail goes to
     * the server log where it's actually useful.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(
            Exception ex,
            HttpServletRequest request) {

        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("Internal Server Error")
                .message("An unexpected error occurred. Please try again or contact support.")
                .path(request.getRequestURI())
                .build();

        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}