package com.java.system.sessionagent.web;

import com.java.system.sessionagent.conversation.port.in.MessageConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public final class WebErrorHandler {

    @ExceptionHandler({BadRequestException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<MessageResponses.ErrorResponse> badRequest(RuntimeException exception) {
        return error(HttpStatus.BAD_REQUEST, "bad_request");
    }

    @ExceptionHandler(MessageConflictException.class)
    ResponseEntity<MessageResponses.ErrorResponse> conflict(MessageConflictException exception) {
        return error(HttpStatus.CONFLICT, "message_conflict");
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<MessageResponses.ErrorResponse> notFound(NotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "not_found");
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<MessageResponses.ErrorResponse> unavailable(RuntimeException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable");
    }

    private static ResponseEntity<MessageResponses.ErrorResponse> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new MessageResponses.ErrorResponse(code));
    }

    static final class BadRequestException extends RuntimeException {
    }

    static final class NotFoundException extends RuntimeException {
    }
}
