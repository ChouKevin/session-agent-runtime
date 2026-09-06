package com.java.system.sessionagent.slack.adapter.sdk;

import com.java.system.sessionagent.slack.application.MessageHandler;
import com.java.system.sessionagent.slack.application.SlackSocketLifecycle;
import com.java.system.sessionagent.slack.configuration.SlackProperties;
import com.java.system.sessionagent.slack.domain.SlackIntakeResult;
import com.java.system.sessionagent.slack.domain.SlackLifecycleState;
import com.java.system.sessionagent.slack.port.out.SlackSocketConnectionListener;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SlackSocketShutdownTest {

    @Test
    void retires_a_runtime_created_after_bounded_stop_while_startup_is_blocked() throws Exception {
        BlockingRuntimeFactory runtimeFactory = new BlockingRuntimeFactory();
        SlackBoltSocketClient socketClient = new SlackBoltSocketClient(properties(), adapter(), runtimeFactory);
        SlackSocketLifecycle lifecycle = new SlackSocketLifecycle(socketClient, properties());

        lifecycle.start();
        runtimeFactory.awaitCreation();
        lifecycle.stop();
        runtimeFactory.allowCreation();
        runtimeFactory.runtime.awaitStop();

        assertThat(lifecycle.state()).isEqualTo(SlackLifecycleState.STOPPED);
        assertThat(runtimeFactory.runtime.startCalls).hasValue(0);
        assertThat(runtimeFactory.runtime.stopCalls).hasValue(1);
    }

    private static SlackProperties properties() {
        return new SlackProperties("xapp-test", "xoxb-test", "UBOT", Duration.ofMillis(10), Duration.ofSeconds(1));
    }

    private static MessageHandler adapter() {
        return new MessageHandler("UBOT", ignored -> SlackIntakeResult.newIgnored());
    }

    private static final class BlockingRuntimeFactory implements SlackSocketRuntimeFactory {

        private final CountDownLatch creationStarted = new CountDownLatch(1);
        private final CountDownLatch creationAllowed = new CountDownLatch(1);
        private final RecordingRuntime runtime = new RecordingRuntime();

        @Override
        public SlackSocketRuntime create(SlackSocketConnectionListener listener) {
            creationStarted.countDown();
            boolean interrupted = false;
            while (creationAllowed.getCount() > 0) {
                try {
                    creationAllowed.await();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return runtime;
        }

        private void awaitCreation() throws InterruptedException {
            assertThat(creationStarted.await(1, TimeUnit.SECONDS)).isTrue();
        }

        private void allowCreation() {
            creationAllowed.countDown();
        }
    }

    private static final class RecordingRuntime implements SlackSocketRuntime {

        private final AtomicInteger startCalls = new AtomicInteger();
        private final AtomicInteger stopCalls = new AtomicInteger();
        private final CountDownLatch stopped = new CountDownLatch(1);

        @Override
        public void startAsync() {
            startCalls.incrementAndGet();
        }

        @Override
        public void stop() {
            stopCalls.incrementAndGet();
            stopped.countDown();
        }

        private void awaitStop() throws InterruptedException {
            assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }
}
