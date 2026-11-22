package com.projectbrain.identity;

import java.util.UUID;

import com.projectbrain.identity.api.CurrentUser;
import com.projectbrain.identity.api.ProfileProvisioner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService implements ProfileProvisioner {

	private final JdbcClient jdbc;

	ProfileService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public Me getOrCreate(CurrentUser user) {
		jdbc.sql("SELECT set_config('app.current_user_id', :userId, true)")
			.param("userId", user.id().toString())
			.query(String.class)
			.single();
		ensureExists(user);
		return jdbc.sql("SELECT id, email, display_name FROM brain.profiles WHERE id = :id")
			.param("id", user.id())
			.query((row, rowNumber) -> new Me(row.getObject("id", UUID.class), row.getString("email"),
					row.getString("display_name")))
			.single();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void ensureExists(CurrentUser user) {
		jdbc.sql("""
				INSERT INTO brain.profiles (id, email, display_name)
				VALUES (:id, :email, :displayName)
				ON CONFLICT (id) DO NOTHING
				""")
			.param("id", user.id())
			.param("email", user.email())
			.param("displayName", user.email().substring(0, user.email().indexOf('@')))
			.update();
	}

	public record Me(UUID id, String email, String displayName) {
	}

}
