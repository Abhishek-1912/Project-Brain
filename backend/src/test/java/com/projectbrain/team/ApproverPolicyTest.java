package com.projectbrain.team;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.projectbrain.team.api.ApprovalBasis;
import com.projectbrain.team.api.Approvers;
import com.projectbrain.team.api.ApproverPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ApproverPolicyTest {

	private static final UUID OWNER = UUID.randomUUID();
	private static final UUID SECOND_OWNER = UUID.randomUUID();
	private static final UUID RAVI = UUID.randomUUID();
	private static final UUID ASHA = UUID.randomUUID();

	static Stream<Arguments> cases() {
		return Stream.of(
				Arguments.of("role holder approves", Set.of(RAVI), Set.of(OWNER), ASHA, true, Set.of(RAVI),
						ApprovalBasis.ROLE_HOLDER),
				Arguments.of("submitter holder may self approve", Set.of(RAVI), Set.of(OWNER), RAVI, true, Set.of(RAVI),
						ApprovalBasis.ROLE_HOLDER),
				Arguments.of("self approval off removes submitter", Set.of(RAVI, ASHA), Set.of(OWNER), RAVI, false,
						Set.of(ASHA), ApprovalBasis.ROLE_HOLDER),
				Arguments.of("no holder falls back to owners", Set.of(), Set.of(OWNER, SECOND_OWNER), RAVI, true,
						Set.of(OWNER, SECOND_OWNER), ApprovalBasis.OWNER_FALLBACK),
				Arguments.of("only holder submitted with self approval off", Set.of(RAVI), Set.of(OWNER), RAVI, false,
						Set.of(OWNER), ApprovalBasis.OWNER_FALLBACK),
				Arguments.of("solo owner may self approve", Set.of(), Set.of(OWNER), OWNER, true, Set.of(OWNER),
						ApprovalBasis.OWNER_FALLBACK),
				Arguments.of("fallback excludes submitting owner", Set.of(), Set.of(OWNER, SECOND_OWNER), OWNER, false,
						Set.of(SECOND_OWNER), ApprovalBasis.OWNER_FALLBACK),
				Arguments.of("solo owner blocked with self approval off", Set.of(OWNER), Set.of(OWNER), OWNER, false,
						Set.of(), ApprovalBasis.OWNER_FALLBACK));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("cases")
	void resolvesRoleHolderFallbackAndSelfApprovalCases(String name, Set<UUID> holders, Set<UUID> owners, UUID submitter,
			boolean allowSelfApproval, Set<UUID> expected, ApprovalBasis basis) {
		Approvers approvers = ApproverPolicy.resolve(holders, owners, submitter, allowSelfApproval);

		assertThat(approvers.userIds()).isEqualTo(expected);
		assertThat(approvers.basis()).isEqualTo(basis);
	}

}
