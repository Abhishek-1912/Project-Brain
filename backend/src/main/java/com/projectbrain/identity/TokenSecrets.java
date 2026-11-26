package com.projectbrain.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Personal access token secrets: "pb_" plus 32 random bytes. Only their SHA-256 hash is ever stored. */
final class TokenSecrets {

	static final String PREFIX = "pb_";

	static final int LENGTH = 46;

	private static final SecureRandom RANDOM = new SecureRandom();

	private TokenSecrets() {
	}

	static String generate() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static boolean looksValid(String token) {
		return token != null && token.length() == LENGTH && token.startsWith(PREFIX)
				&& token.substring(PREFIX.length()).chars().allMatch(TokenSecrets::isUrlSafe);
	}

	static String hash(String token) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private static boolean isUrlSafe(int c) {
		return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
	}

}
