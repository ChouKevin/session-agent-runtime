package com.example.payment;

public final class FeeFormulaUnavailableException extends RuntimeException {

    public FeeFormulaUnavailableException(PaymentMethod paymentMethod) {
        super("No fee formula is configured for " + paymentMethod);
    }
}
