package com.example.payment;

import java.math.BigDecimal;

public interface FeeFormulaEvaluator {

    BigDecimal evaluate(String formulaJson, BigDecimal amount);
}
