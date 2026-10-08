package com.guxx.ray.ai.common;

import com.guxx.ray.ai.auth.AuthException;
import com.guxx.ray.ai.match.MatchException;
import com.guxx.ray.ai.player.PlayerException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthException.class)
    ResponseEntity<Object> auth(AuthException ex) { return error(ex.status(), ex.getMessage()); }

    @ExceptionHandler(PlayerException.class)
    ResponseEntity<Object> player(PlayerException ex) { return error(ex.status(), ex.getMessage()); }

    @ExceptionHandler(MatchException.class)
    ResponseEntity<Object> match(MatchException ex) { return error(ex.status(), ex.getMessage()); }

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class})
    ResponseEntity<Object> badRequest(Exception ex) { return error(HttpStatus.BAD_REQUEST, "Invalid request parameters"); }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> unauthorized(AuthenticationException ex) {
        return ResponseEntity.status(401).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .cacheControl(CacheControl.noStore()).body(Result.error(401, "Valid Bearer token required"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> forbidden(AccessDeniedException ex) { return error(HttpStatus.FORBIDDEN, "Access denied"); }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Object> dataUnavailable(DataAccessException ex) {
        log.error("Data access failed ({})", ex.getClass().getName());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "Data service unavailable");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex) {
        // Avoid logging upstream URLs/query parameters that may contain API keys or JWTs.
        log.error("Unhandled request exception ({})", ex.getClass().getName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String message = status == null ? "Request failed" : status.getReasonPhrase();
        return ResponseEntity.status(statusCode).headers(headers).cacheControl(CacheControl.noStore())
                .body(Result.error(statusCode.value(), message));
    }

    private ResponseEntity<Object> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Result.error(status.value(), message));
    }
}
