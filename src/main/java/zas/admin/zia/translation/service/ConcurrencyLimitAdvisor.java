package zas.admin.zia.translation.service;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Semaphore;

/**
 * Caps the number of model calls in progress at the same time, across all requests and jobs of the application
 * (translation, OCR, extraction): pages are now processed in parallel, and the model server must not receive more
 * requests than it can batch. A call waits for a free slot (fair: first come, first served).
 * <p>
 * Ordered just before the model call, inside the tool-calling loop: the slot is held only while the model works,
 * not while a tool runs locally. A streamed call holds its slot until the stream completes, fails or is cancelled.
 */
class ConcurrencyLimitAdvisor implements CallAdvisor, StreamAdvisor {

    private final String name;
    private final Semaphore slots;

    ConcurrencyLimitAdvisor(String name, int maxConcurrentCalls) {
        if (maxConcurrentCalls < 1) {
            throw new IllegalArgumentException("Maximum concurrent model calls must be >= 1");
        }
        this.name = name;
        this.slots = new Semaphore(maxConcurrentCalls, true);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        acquire();
        try {
            return chain.nextCall(request);
        } finally {
            slots.release();
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // waiting for a slot blocks: done on boundedElastic, never on a Reactor event-loop thread
        return Flux.usingWhen(
                Mono.fromCallable(() -> {
                    acquire();
                    return Boolean.TRUE;
                }).subscribeOn(Schedulers.boundedElastic()),
                slot -> chain.nextStream(request),
                slot -> Mono.fromRunnable(slots::release));
    }

    /** Waits for a slot; a cancelled page task (interrupted) stops waiting instead of calling the model. */
    private void acquire() {
        try {
            slots.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a free model call slot", interrupted);
        }
    }

    int availableSlots() {
        return slots.availablePermits();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getOrder() {
        // just outside FinishReasonAdvisor (LOWEST_PRECEDENCE - 1), itself just outside the model call
        return Ordered.LOWEST_PRECEDENCE - 2;
    }
}
