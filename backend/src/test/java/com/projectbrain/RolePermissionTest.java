package com.projectbrain;

import static org.assertj.core.api.Assertions.assertThat;

import com.projectbrain.organization.api.Permission;
import com.projectbrain.organization.api.Role;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RolePermissionTest {

	@ParameterizedTest(name = "{0} {1} = {2}")
	@CsvSource({
			"OWNER,  VIEW,              true",
			"OWNER,  EDIT_TASKS,        true",
			"OWNER,  MANAGE_PROJECTS,   true",
			"OWNER,  VIEW_AUDIT,        true",
			"OWNER,  EDIT_ORGANIZATION, true",
			"ADMIN,  VIEW,              true",
			"ADMIN,  EDIT_TASKS,        true",
			"ADMIN,  MANAGE_PROJECTS,   true",
			"ADMIN,  VIEW_AUDIT,        true",
			"ADMIN,  EDIT_ORGANIZATION, false",
			"MEMBER, VIEW,              true",
			"MEMBER, EDIT_TASKS,        true",
			"MEMBER, MANAGE_PROJECTS,   false",
			"MEMBER, VIEW_AUDIT,        false",
			"MEMBER, EDIT_ORGANIZATION, false",
			"VIEWER, VIEW,              true",
			"VIEWER, EDIT_TASKS,        false",
			"VIEWER, MANAGE_PROJECTS,   false",
			"VIEWER, VIEW_AUDIT,        false",
			"VIEWER, EDIT_ORGANIZATION, false" })
	void roleMatrixMatchesSpecSection9(Role role, Permission permission, boolean allowed) {
		assertThat(role.allows(permission)).isEqualTo(allowed);
	}

}
