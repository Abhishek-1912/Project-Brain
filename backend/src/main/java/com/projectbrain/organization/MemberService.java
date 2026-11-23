package com.projectbrain.organization;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.projectbrain.audit.api.AuditLog;
import com.projectbrain.organization.MemberController.Member;
import com.projectbrain.organization.api.MemberRemovalListener;
import com.projectbrain.organization.api.OrgTx;
import com.projectbrain.organization.api.Permission;
import com.projectbrain.organization.api.Role;
import com.projectbrain.shared.web.ConflictException;
import com.projectbrain.shared.web.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class MemberService {

	private static final String ENTITY_TYPE = "MEMBER";

	private final OrgTx orgTx;

	private final JdbcClient jdbc;

	private final AuditLog auditLog;

	private final List<MemberRemovalListener> removalListeners;

	MemberService(OrgTx orgTx, JdbcClient jdbc, AuditLog auditLog, List<MemberRemovalListener> removalListeners) {
		this.orgTx = orgTx;
		this.jdbc = jdbc;
		this.auditLog = auditLog;
		this.removalListeners = removalListeners;
	}

	Member changeRole(UUID orgId, UUID userId, Role role, long version) {
		return orgTx.inOrg(orgId, Permission.MANAGE_MEMBERS, () -> {
			List<UUID> owners = lockOwners(orgId);
			Member member = lockMember(orgId, userId);
			if (member.version() != version) {
				throw new ConflictException("The member was changed by someone else. Reload and try again.");
			}
			if (member.role() == role) {
				return member;
			}
			requireAnotherOwner(member, owners);
			jdbc.sql("""
					UPDATE brain.organization_members
					SET role = :role, updated_at = now(), version = version + 1
					WHERE organization_id = :orgId AND user_id = :userId
					""")
				.param("role", role.name())
				.param("orgId", orgId)
				.param("userId", userId)
				.update();
			auditLog.record(orgId, "MEMBER_ROLE_CHANGED", ENTITY_TYPE, userId,
					Map.of("name", member.email(), "previousRole", member.role().name(), "role", role.name()));
			return new Member(member.userId(), member.email(), member.displayName(), role, version + 1);
		});
	}

	void remove(UUID orgId, UUID userId) {
		orgTx.inOrg(orgId, Permission.MANAGE_MEMBERS, () -> {
			List<UUID> owners = lockOwners(orgId);
			Member member = lockMember(orgId, userId);
			requireAnotherOwner(member, owners);
			removalListeners.forEach(listener -> listener.onMemberRemoved(orgId, userId));
			jdbc.sql("DELETE FROM brain.organization_members WHERE organization_id = :orgId AND user_id = :userId")
				.param("orgId", orgId)
				.param("userId", userId)
				.update();
			auditLog.record(orgId, "MEMBER_REMOVED", ENTITY_TYPE, userId,
					Map.of("name", member.email(), "role", member.role().name()));
			return null;
		});
	}

	/** Holding every Owner row makes concurrent changes take turns, so the last Owner cannot be lost. */
	private List<UUID> lockOwners(UUID orgId) {
		return jdbc.sql("""
				SELECT user_id FROM brain.organization_members
				WHERE organization_id = :orgId AND role = 'OWNER'
				ORDER BY user_id
				FOR UPDATE
				""")
			.param("orgId", orgId)
			.query(UUID.class)
			.list();
	}

	private Member lockMember(UUID orgId, UUID userId) {
		return jdbc.sql("""
				SELECT m.user_id, p.email, COALESCE(p.display_name, p.email) AS display_name, m.role, m.version
				FROM brain.organization_members m
				JOIN brain.profiles p ON p.id = m.user_id
				WHERE m.organization_id = :orgId AND m.user_id = :userId
				FOR UPDATE OF m
				""")
			.param("orgId", orgId)
			.param("userId", userId)
			.query((row, rowNumber) -> new Member(row.getObject("user_id", UUID.class), row.getString("email"),
					row.getString("display_name"), Role.valueOf(row.getString("role")), row.getLong("version")))
			.optional()
			.orElseThrow(() -> new NotFoundException("Member not found."));
	}

	private static void requireAnotherOwner(Member member, List<UUID> owners) {
		if (member.role() == Role.OWNER && owners.size() <= 1) {
			throw new ConflictException("An organization must keep at least one owner.");
		}
	}

}
