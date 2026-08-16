package com.example.payment;

import java.math.BigDecimal;
import java.util.Objects;

public final class PaymentFeeCalculator {

    private final PaymentFeeSettings settings;
    private final FeeFormulaEvaluator feeFormulaEvaluator;

    public PaymentFeeCalculator(PaymentFeeSettings settings, FeeFormulaEvaluator feeFormulaEvaluator) {
        this.settings = Objects.requireNonNull(settings);
        this.feeFormulaEvaluator = Objects.requireNonNull(feeFormulaEvaluator);
    }

    public BigDecimal calculate(PaymentMethod paymentMethod, BigDecimal amount) {
        Objects.requireNonNull(paymentMethod);
        Objects.requireNonNull(amount);
        String formulaJson = settings.loadFeeFormulaJson(paymentMethod)
                .orElseThrow(() -> new FeeFormulaUnavailableException(paymentMethod));
        return feeFormulaEvaluator.evaluate(formulaJson, amount);
    }
}
