package com.example.payment;

import java.util.Optional;

public interface PaymentFeeSettings {

    Optional<String> loadFeeFormulaJson(PaymentMethod paymentMethod);
}
