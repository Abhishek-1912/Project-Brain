package com.projectbrain.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenSecretsTest {

	@Test
	void generatedTokenHasPrefixAndUrlSafeRandomPart() {
		String token = TokenSecrets.generate();

		assertThat(token).hasSize(46).startsWith("pb_").matches("pb_[A-Za-z0-9_-]{43}");
	}

	@Test
	void twoTokensDiffer() {
		assertThat(TokenSecrets.generate()).isNotEqualTo(TokenSecrets.generate());
	}

	@Test
	void hashIsStableLowercaseSha256Hex() {
		assertThat(TokenSecrets.hash("abc"))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

}
