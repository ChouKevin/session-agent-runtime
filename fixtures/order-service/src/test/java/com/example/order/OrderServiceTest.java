package com.example.order;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderServiceTest {

    @Test
    void cancelsAndPersistsAnOrderInACancellableState() {
        RecordingRepository repository = new RecordingRepository(new Order("order-1", OrderStatus.CONFIRMED));
        OrderService service = new OrderService(repository);

        Order cancelledOrder = service.cancel("order-1");

        assertThat(cancelledOrder.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(repository.savedOrder).isEqualTo(cancelledOrder);
    }

    @Test
    void rejectsANonCancellableOrderWithoutPersistingIt() {
        RecordingRepository repository = new RecordingRepository(new Order("order-1", OrderStatus.SHIPPED));
        OrderService service = new OrderService(repository);

        assertThatThrownBy(() -> service.cancel("order-1"))
                .isInstanceOf(OrderCancellationException.class);
        assertThat(repository.savedOrder).isNull();
    }

    private static final class RecordingRepository implements OrderRepository {

        private final Order order;
        private Order savedOrder;

        private RecordingRepository(Order order) {
            this.order = order;
        }

        @Override
        public Optional<Order> findById(String orderId) {
            return Optional.of(order);
        }

        @Override
        public void save(Order order) {
            savedOrder = order;
        }
    }
}
