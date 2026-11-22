package com.projectbrain.organization.api;

import java.util.EnumSet;
import java.util.Set;

public enum Role {

	OWNER(EnumSet.allOf(Permission.class)),
	ADMIN(EnumSet.of(Permission.VIEW, Permission.EDIT_TASKS, Permission.MANAGE_PROJECTS, Permission.VIEW_AUDIT)),
	MEMBER(EnumSet.of(Permission.VIEW, Permission.EDIT_TASKS)),
	VIEWER(EnumSet.of(Permission.VIEW));

	private final Set<Permission> permissions;

	Role(Set<Permission> permissions) {
		this.permissions = permissions;
	}

	public boolean allows(Permission permission) {
		return permissions.contains(permission);
	}

}
