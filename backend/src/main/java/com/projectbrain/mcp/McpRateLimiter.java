package com.projectbrain.mcp;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** Allows each token a fixed number of MCP requests per rolling minute. Kept in memory, so one backend instance. */
@Component
class McpRateLimiter {

	static final int LIMIT = 60;

	private static final long WINDOW_MILLIS = 60_000;

	private final Map<UUID, Deque<Long>> calls = new ConcurrentHashMap<>();

	private final Clock clock = Clock.systemUTC();

	boolean tryAcquire(UUID tokenId) {
		long now = clock.millis();
		Deque<Long> times = calls.computeIfAbsent(tokenId, id -> new ArrayDeque<>());
		synchronized (times) {
			while (!times.isEmpty() && times.peekFirst() <= now - WINDOW_MILLIS) {
				times.pollFirst();
			}
			if (times.size() >= LIMIT) {
				return false;
			}
			times.addLast(now);
			return true;
		}
	}

}
