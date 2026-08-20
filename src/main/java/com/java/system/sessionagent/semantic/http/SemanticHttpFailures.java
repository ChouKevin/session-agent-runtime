package com.java.system.sessionagent.semantic.http;

import com.java.system.sessionagent.semantic.SemanticFailure;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.util.Optional;

final class SemanticHttpFailures {

    private SemanticHttpFailures() {
    }

    static SemanticFailure classify(RestClientException exception) {
        Objects.requireNonNull(exception, "Semantic REST client failure must not be null");
        if (hasTimeoutCause(exception)) {
            return SemanticFailure.transientFailure(Optional.empty(), exception);
        }
        return SemanticFailure.invalidResponse();
    }

    private static boolean hasTimeoutCause(Throwable failure) {
        Throwable current = failure;
        while (Objects.nonNull(current)) {
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                return false;
            }
            current = cause;
        }
        return false;
    }
}
