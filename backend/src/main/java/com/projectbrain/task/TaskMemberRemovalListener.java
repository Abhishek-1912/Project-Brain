package com.projectbrain.task;

import java.util.UUID;

import com.projectbrain.organization.api.MemberRemovalListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class TaskMemberRemovalListener implements MemberRemovalListener {

	private final JdbcClient jdbc;

	TaskMemberRemovalListener(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void onMemberRemoved(UUID orgId, UUID userId) {
		jdbc.sql("""
				UPDATE brain.tasks
				SET assignee_id = NULL, updated_at = now(), version = version + 1
				WHERE organization_id = :orgId AND assignee_id = :userId
				""")
			.param("orgId", orgId)
			.param("userId", userId)
			.update();
	}

}
