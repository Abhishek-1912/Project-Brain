package com.projectbrain.requirement.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only access to reviewed requirement knowledge. Must run inside OrgTx for the same organization. */
public interface KnowledgeSourceLookup {

	/** Empty when the item is missing, belongs to another project, or is no longer reviewed knowledge. */
	Optional<KnowledgeContent> load(UUID orgId, UUID projectId, KnowledgeSourceType type, UUID sourceId);

	/** Every item in the project that currently qualifies, with one ARCHITECTURE entry keyed by the project id. */
	List<KnowledgeRef> qualifying(UUID orgId, UUID projectId);

}
