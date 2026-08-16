package com.example.payment;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/payment-methods")
public final class PaymentQueryController {

    @GetMapping
    public List<PaymentMethod> paymentMethods() {
        return List.of(PaymentMethod.values());
    }
}
