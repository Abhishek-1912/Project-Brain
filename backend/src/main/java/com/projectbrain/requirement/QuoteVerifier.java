package com.projectbrain.requirement;

import java.util.Locale;

final class QuoteVerifier {

	private QuoteVerifier() {
	}

	static boolean isVerified(String quote, String documentText) {
		if (quote == null || quote.isBlank() || documentText == null) {
			return false;
		}
		return normalize(documentText).contains(normalize(quote));
	}

	private static String normalize(String value) {
		return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
	}

}
