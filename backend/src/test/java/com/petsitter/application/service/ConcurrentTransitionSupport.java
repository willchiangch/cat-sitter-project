package com.petsitter.application.service;

import com.petsitter.application.exception.GlobalExceptionHandler;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同一張訂單的併發狀態轉移測試共用工具（TS-005-02 / TS-006-05 / TS-006-06）。
 *
 * <p>每個 action 一個執行緒，用 CyclicBarrier 同時起跑。失敗的請求一律交給正式的
 * {@link GlobalExceptionHandler} 換算成 HTTP 狀態碼，所以斷言的是使用者實際會拿到的回應（例如 409），
 * 而不是某個內部例外型別。handler 沒有對應、或對應到非 4xx 語意的例外（例如 unique 衝突被轉成 400
 * DATA_ERROR）會原樣留在 {@code statuses} 裡讓斷言抓出來。
 */
final class ConcurrentTransitionSupport {

    private static final GlobalExceptionHandler HANDLER = new GlobalExceptionHandler();

    record Result(int success, List<Integer> failureStatuses, List<Throwable> failures) {
    }

    private ConcurrentTransitionSupport() {
    }

    static Result runConcurrently(List<Runnable> actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(actions.size());
        CyclicBarrier barrier = new CyclicBarrier(actions.size());
        AtomicInteger success = new AtomicInteger();
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Runnable action : actions) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        throw new IllegalStateException("barrier 失敗", e);
                    }
                    try {
                        action.run();
                        success.incrementAndGet();
                    } catch (RuntimeException e) {
                        failures.add(e);
                        statuses.add(httpStatusOf(e));
                    }
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        return new Result(success.get(), List.copyOf(statuses), List.copyOf(failures));
    }

    /** 走正式 GlobalExceptionHandler 換算；沒有對應 handler 的例外回 500（與 Spring 預設一致）。 */
    static int httpStatusOf(RuntimeException e) {
        if (e instanceof ObjectOptimisticLockingFailureException o) {
            return HANDLER.handleOptimisticLock(o).getStatusCode().value();
        }
        if (e instanceof IllegalStateException s) {
            return HANDLER.handleIllegalState(s).getStatusCode().value();
        }
        if (e instanceof IllegalArgumentException a) {
            return HANDLER.handleIllegalArgument(a).getStatusCode().value();
        }
        if (e instanceof org.springframework.dao.DataIntegrityViolationException d) {
            return HANDLER.handleDataIntegrityViolation(d).getStatusCode().value();
        }
        return 500;
    }
}
