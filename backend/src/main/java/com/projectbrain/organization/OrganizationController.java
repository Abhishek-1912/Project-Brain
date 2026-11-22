package com.projectbrain.organization;

import java.util.UUID;

import com.projectbrain.organization.OrganizationService.Organization;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations")
class OrganizationController {

	private final OrganizationService organizations;

	OrganizationController(OrganizationService organizations) {
		this.organizations = organizations;
	}

	@GetMapping
	PageResponse<Organization> list(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return organizations.list(PageParams.of(page, size));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	Organization create(@Valid @RequestBody CreateRequest request) {
		return organizations.create(request.name(), request.slug());
	}

	@GetMapping("/{orgId}")
	Organization get(@PathVariable UUID orgId) {
		return organizations.get(orgId);
	}

	@PatchMapping("/{orgId}")
	Organization update(@PathVariable UUID orgId, @Valid @RequestBody PatchRequest request) {
		return organizations.update(orgId, request.name(), request.version());
	}

	record CreateRequest(@NotBlank @Size(max = 120) String name,
			@NotNull @Size(min = 3, max = 63) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$") String slug) {
	}

	record PatchRequest(@Size(min = 1, max = 120) @Pattern(regexp = "(?s).*\\S.*") String name,
			@NotNull Long version) {
	}

}
