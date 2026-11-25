package com.projectbrain.requirement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.function.Supplier;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.project.api.ProjectLookup;
import com.projectbrain.project.api.ProjectStatus;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.UnprocessableException;
import org.junit.jupiter.api.Test;

class ArchitectureRunnerTest {

	@Test
	void storiesChangingBeforeTheRunStartsFailAsInputsChanged() {
		assertInputsChanged(new ConflictException("Approve at least one user story"));
		assertInputsChanged(new UnprocessableException("Too many stories"));
	}

	private static void assertInputsChanged(RuntimeException loadFailure) {
		OrgTx orgTx = mock(OrgTx.class);
		AiRunStore runs = mock(AiRunStore.class);
		ArchitectureInputService inputs = mock(ArchitectureInputService.class);
		ProjectLookup projects = mock(ProjectLookup.class);
		when(orgTx.inOrgAs(any(), any(), any(), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(3).get());
		when(projects.requireStatus(any(), any())).thenReturn(ProjectStatus.ACTIVE);
		when(runs.markRunning(any(), any())).thenReturn(true);
		when(inputs.load(any(), any())).thenThrow(loadFailure);
		ArchitectureRunner runner = new ArchitectureRunner(orgTx, runs, mock(ArchitectureVersionStore.class), inputs,
				new ArchitectureValidator(), mock(AiServiceClient.class), projects, mock(AuditLog.class));

		assertThatThrownBy(() -> runner.run(new AiRunWorker.Job(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				null, UUID.randomUUID(), "ARCHITECTURE")))
			.isInstanceOfSatisfying(AiServiceException.class,
					error -> org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo("INPUTS_CHANGED"));
	}

}
