package com.projectbrain.requirement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.function.Supplier;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.project.api.ProjectLookup;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class AiRunWorkerTest {

	@Test
	void retriesMarkingARunFailedWhenTheDatabaseBlips() {
		OrgTx orgTx = mock(OrgTx.class);
		AiRunStore store = mock(AiRunStore.class);
		UUID run = UUID.randomUUID();
		UUID org = UUID.randomUUID();
		when(orgTx.inOrgAs(any(), any(), any(), any())).thenThrow(new QueryTimeoutException("database"));
		when(orgTx.withOrgContext(any(), any(), any())).thenThrow(new QueryTimeoutException("database"))
			.thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
		AiRunWorker worker = new AiRunWorker(orgTx, store, mock(AnalysisService.class), mock(StoryService.class),
				mock(AiServiceClient.class), mock(ProjectLookup.class), mock(AuditLog.class));

		worker.submit(new AiRunWorker.Job(run, org, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ANALYSIS"));

		verify(store, timeout(5000)).fail(eq(org), eq(run), eq("INTERNAL_ERROR"), isNull());
		worker.stop();
	}

}
