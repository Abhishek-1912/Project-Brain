package com.projectbrain.organization;

import java.util.List;
import java.util.UUID;

import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.organization.api.Role;
import com.projectbrain.shared.web.PageParams;
import com.projectbrain.shared.web.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MemberController {

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	MemberController(OrgTx orgTx, JdbcClient jdbc) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
	}

	@GetMapping("/api/v1/organizations/{orgId}/members")
	PageResponse<Member> list(@PathVariable UUID orgId, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		PageParams paging = PageParams.of(page, size);
		return orgTx.inOrg(orgId, Permission.VIEW, () -> {
			long total = jdbc.sql("SELECT count(*) FROM brain.organization_members WHERE organization_id = :orgId")
				.param("orgId", orgId)
				.query(Long.class)
				.single();
			List<Member> items = jdbc.sql("""
					SELECT m.user_id, p.email, COALESCE(p.display_name, p.email) AS display_name, m.role
					FROM brain.organization_members m
					JOIN brain.profiles p ON p.id = m.user_id
					WHERE m.organization_id = :orgId
					ORDER BY lower(COALESCE(p.display_name, p.email)), m.user_id
					LIMIT :limit OFFSET :offset
					""")
				.param("orgId", orgId)
				.param("limit", paging.size())
				.param("offset", paging.offset())
				.query((row, rowNumber) -> new Member(row.getObject("user_id", UUID.class), row.getString("email"),
						row.getString("display_name"), Role.valueOf(row.getString("role"))))
				.list();
			return new PageResponse<>(items, paging.page(), paging.size(), total);
		});
	}

	record Member(UUID userId, String email, String displayName, Role role) {
	}

}
