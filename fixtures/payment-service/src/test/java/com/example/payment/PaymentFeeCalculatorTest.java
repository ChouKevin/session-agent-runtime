package com.example.payment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentFeeCalculatorTest {

    @Test
    void delegatesTheRuntimeFormulaFromSettingsToTheEvaluator() {
        RecordingEvaluator evaluator = new RecordingEvaluator();
        PaymentFeeSettings settings = paymentMethod -> Optional.of("runtime-settings");
        PaymentFeeCalculator calculator = new PaymentFeeCalculator(settings, evaluator);

        BigDecimal fee = calculator.calculate(PaymentMethod.WALLET, BigDecimal.ONE);

        assertThat(fee).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(evaluator.formulaJson).isEqualTo("runtime-settings");
        assertThat(evaluator.amount).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void rejectsPaymentMethodsWithoutARuntimeFormula() {
        PaymentFeeSettings settings = paymentMethod -> Optional.empty();
        PaymentFeeCalculator calculator = new PaymentFeeCalculator(settings, (formulaJson, amount) -> amount);

        assertThatThrownBy(() -> calculator.calculate(PaymentMethod.BANK_TRANSFER, BigDecimal.ONE))
                .isInstanceOf(FeeFormulaUnavailableException.class);
    }

    private static final class RecordingEvaluator implements FeeFormulaEvaluator {

        private String formulaJson;
        private BigDecimal amount;

        @Override
        public BigDecimal evaluate(String formulaJson, BigDecimal amount) {
            this.formulaJson = formulaJson;
            this.amount = amount;
            return amount;
        }
    }
}
