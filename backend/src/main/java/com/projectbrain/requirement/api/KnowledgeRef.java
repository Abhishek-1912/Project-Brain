package com.projectbrain.requirement.api;

import java.util.UUID;

public record KnowledgeRef(KnowledgeSourceType type, UUID sourceId) {
}
