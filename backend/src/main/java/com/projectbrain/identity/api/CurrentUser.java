package com.projectbrain.identity.api;

import java.util.UUID;

public record CurrentUser(UUID id, String email) {
}
