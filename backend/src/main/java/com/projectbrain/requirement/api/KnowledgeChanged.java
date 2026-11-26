package com.projectbrain.requirement.api;

import java.util.UUID;

/**
 * Published inside the transaction that changed the item, so after-commit listeners see only committed changes. An
 * ARCHITECTURE change uses the project id as its source id.
 */
public record KnowledgeChanged(UUID orgId, UUID projectId, KnowledgeSourceType type, UUID sourceId, UUID actorId) {
}
