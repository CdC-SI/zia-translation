package zas.admin.zia.translation.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConcurrencyLimitAdvisorTest {

    private final ChatClientRequest request = mock(ChatClientRequest.class);
    private final ChatClientResponse response = mock(ChatClientResponse.class);

    @Test
    void call_neverMoreThanTheCapInProgress() throws Exception {
        ConcurrencyLimitAdvisor advisor = new ConcurrencyLimitAdvisor("test", 3);
        AtomicInteger inProgress = new AtomicInteger();
        AtomicInteger maxInProgress = new AtomicInteger();
        CallAdvisorChain fakeModel = mock(CallAdvisorChain.class);
        when(fakeModel.nextCall(any())).thenAnswer(invocation -> {
            maxInProgress.accumulateAndGet(inProgress.incrementAndGet(), Math::max);
            Thread.sleep(20);
            inProgress.decrementAndGet();
            return response;
        });

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ChatClientResponse>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                calls.add(callers.submit(() -> advisor.adviseCall(request, fakeModel)));
            }
            for (Future<ChatClientResponse> call : calls) {
                assertThat(call.get()).isSameAs(response);
            }
        }

        assertThat(maxInProgress.get()).isEqualTo(3);
        assertThat(advisor.availableSlots()).isEqualTo(3);
    }

    @Test
    void call_failingModelCall_releasesItsSlot() {
        ConcurrencyLimitAdvisor advisor = new ConcurrencyLimitAdvisor("test", 1);
        CallAdvisorChain failingModel = mock(CallAdvisorChain.class);
        when(failingModel.nextCall(any())).thenThrow(new IllegalStateException("server down"));

        assertThatThrownBy(() -> advisor.adviseCall(request, failingModel)).hasMessage("server down");
        assertThat(advisor.availableSlots()).isEqualTo(1);
    }

    @Test
    void call_interruptedWhileWaiting_givesUpWithoutTakingASlot() throws Exception {
        ConcurrencyLimitAdvisor advisor = new ConcurrencyLimitAdvisor("test", 1);
        CountDownLatch slotTaken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CallAdvisorChain slowModel = mock(CallAdvisorChain.class);
        when(slowModel.nextCall(any())).thenAnswer(invocation -> {
            slotTaken.countDown();
            release.await();
            return response;
        });
        Thread holder = Thread.ofVirtual().start(() -> advisor.adviseCall(request, slowModel));
        slotTaken.await();
        List<Throwable> waiterFailure = new ArrayList<>();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                advisor.adviseCall(request, slowModel);
            } catch (IllegalStateException expected) {
                waiterFailure.add(expected);
            }
        });

        waiter.interrupt();   // e.g. a page task cancelled because another page failed
        waiter.join(2000);
        release.countDown();
        holder.join(2000);

        assertThat(waiterFailure).singleElement().satisfies(failure ->
                assertThat(failure).hasMessageContaining("Interrupted while waiting"));
        assertThat(advisor.availableSlots()).isEqualTo(1);
    }

    @Test
    void stream_holdsItsSlotUntilTheStreamEnds() throws Exception {
        ConcurrencyLimitAdvisor advisor = new ConcurrencyLimitAdvisor("test", 2);
        Sinks.Many<ChatClientResponse> tokens = Sinks.many().unicast().onBackpressureBuffer();
        StreamAdvisorChain fakeModel = mock(StreamAdvisorChain.class);
        when(fakeModel.nextStream(any())).thenReturn(tokens.asFlux());

        List<ChatClientResponse> received = new ArrayList<>();
        Disposable subscription = advisor.adviseStream(request, fakeModel).subscribe(received::add);
        awaitSlots(advisor, 1);
        tokens.tryEmitNext(response);
        tokens.tryEmitComplete();
        awaitSlots(advisor, 2);

        assertThat(received).containsExactly(response);
        assertThat(subscription.isDisposed()).isTrue();
    }

    @Test
    void stream_cancelledByTheClient_releasesItsSlot() throws Exception {
        ConcurrencyLimitAdvisor advisor = new ConcurrencyLimitAdvisor("test", 1);
        StreamAdvisorChain endlessModel = mock(StreamAdvisorChain.class);
        when(endlessModel.nextStream(any())).thenReturn(Flux.never());

        Disposable subscription = advisor.adviseStream(request, endlessModel).subscribe();
        awaitSlots(advisor, 0);
        subscription.dispose();   // the SSE client went away

        awaitSlots(advisor, 1);
    }

    @Test
    void constructor_rejectsAnInvalidCap() {
        assertThatThrownBy(() -> new ConcurrencyLimitAdvisor("test", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void awaitSlots(ConcurrencyLimitAdvisor advisor, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (advisor.availableSlots() != expected && System.nanoTime() < deadline) {
            Thread.sleep(Duration.ofMillis(5));
        }
        assertThat(advisor.availableSlots()).isEqualTo(expected);
    }
}
