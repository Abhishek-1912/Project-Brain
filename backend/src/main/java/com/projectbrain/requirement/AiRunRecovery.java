package com.projectbrain.requirement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Runs live only in this server's memory, so any run still active at startup can never finish. */
@Component
public class AiRunRecovery {

	private static final Logger log = LoggerFactory.getLogger(AiRunRecovery.class);

	private final JdbcClient jdbc;

	AiRunRecovery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@EventListener(ApplicationReadyEvent.class)
	void onStartup() {
		int recovered = recover();
		if (recovered > 0) {
			log.warn("Marked {} abandoned AI runs as failed", recovered);
		}
	}

	public int recover() {
		return jdbc.sql("SELECT brain.fail_abandoned_ai_runs()").query(Integer.class).single();
	}

}
