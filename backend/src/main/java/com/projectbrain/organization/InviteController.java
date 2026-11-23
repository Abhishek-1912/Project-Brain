package com.projectbrain.organization;

import java.util.UUID;

import com.projectbrain.organization.InviteService.AcceptedInvite;
import com.projectbrain.organization.InviteService.CreatedInvite;
import com.projectbrain.organization.InviteService.Invite;
import com.projectbrain.organization.api.Role;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InviteController {

	private final InviteService invites;

	InviteController(InviteService invites) {
		this.invites = invites;
	}

	@GetMapping("/api/v1/organizations/{orgId}/invites")
	PageResponse<Invite> list(@PathVariable UUID orgId, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return invites.listPending(orgId, PageParams.of(page, size));
	}

	@PostMapping("/api/v1/organizations/{orgId}/invites")
	ResponseEntity<CreatedInvite> create(@PathVariable UUID orgId, @Valid @RequestBody CreateRequest request) {
		CreatedInvite invite = invites.create(orgId, request.email(), Role.valueOf(request.role()));
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(invite);
	}

	@DeleteMapping("/api/v1/organizations/{orgId}/invites/{inviteId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void cancel(@PathVariable UUID orgId, @PathVariable UUID inviteId) {
		invites.cancel(orgId, inviteId);
	}

	@PostMapping("/api/v1/invites/accept")
	AcceptedInvite accept(@Valid @RequestBody AcceptRequest request) {
		return invites.accept(request.token());
	}

	record CreateRequest(@NotBlank @Email @Size(max = 320) String email,
			@NotNull @Pattern(regexp = "ADMIN|MEMBER|VIEWER") String role) {
	}

	record AcceptRequest(@NotBlank String token) {
	}

}
