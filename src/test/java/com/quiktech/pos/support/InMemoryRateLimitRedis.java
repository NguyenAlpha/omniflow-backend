package com.quiktech.pos.support;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class InMemoryRateLimitRedis {

    private final Map<String, byte[]> entries = new HashMap<>();
    private final AtomicInteger reads = new AtomicInteger();
    private volatile boolean paused;
    private final StatefulRedisConnection<byte[], byte[]> connection;

    @SuppressWarnings("unchecked")
    public InMemoryRateLimitRedis() {
        RedisAsyncCommands<byte[], byte[]> commands = mock(RedisAsyncCommands.class, call -> {
            if (call.getMethod().getName().equals("get")) {
                reads.incrementAndGet();
                if (paused) {
                    return new TestFuture<byte[]>();
                }
                return completed(read(call.getArgument(0)));
            }
            if (call.getMethod().getName().equals("eval")) {
                Object[] arguments = call.getRawArguments();
                Object[] keys = (Object[]) arguments[2];
                byte[][] values = (byte[][]) arguments[3];
                return completed(compareAndSwap((byte[]) keys[0], values));
            }
            return RETURNS_DEFAULTS.answer(call);
        });
        connection = mock(StatefulRedisConnection.class);
        when(connection.async()).thenReturn(commands);
    }

    public StatefulRedisConnection<byte[], byte[]> connection() {
        return connection;
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public int reads() {
        return reads.get();
    }

    public synchronized void clear() {
        entries.clear();
    }

    private synchronized byte[] read(byte[] key) {
        return entries.get(new String(key, StandardCharsets.UTF_8));
    }

    private synchronized boolean compareAndSwap(byte[] key, byte[][] values) {
        String name = new String(key, StandardCharsets.UTF_8);
        byte[] expected = values.length == 2 ? null : values[0];
        byte[] replacement = values.length == 2 ? values[0] : values[1];
        if (!Arrays.equals(entries.get(name), expected)) {
            return false;
        }
        entries.put(name, replacement);
        return true;
    }

    private static <T> TestFuture<T> completed(T value) {
        TestFuture<T> future = new TestFuture<>();
        future.complete(value);
        return future;
    }

    private static class TestFuture<T> extends CompletableFuture<T> implements RedisFuture<T> {
        @Override
        public String getError() {
            return null;
        }

        @Override
        public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                get(timeout, unit);
                return true;
            } catch (java.util.concurrent.TimeoutException failure) {
                return false;
            } catch (java.util.concurrent.ExecutionException failure) {
                return true;
            }
        }
    }
}
