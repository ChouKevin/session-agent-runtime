package com.java.system.sessionagent.conversation.port.out;

/**
 * Signals that a persistence commit lost its worker claim fence and therefore
 * committed no conversation state.
 */
public final class StaleWorkClaimException extends RuntimeException {

    public StaleWorkClaimException() {
        super("Message work claim is no longer live");
    }
}
