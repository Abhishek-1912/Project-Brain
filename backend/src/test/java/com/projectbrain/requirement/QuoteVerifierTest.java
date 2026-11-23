package com.projectbrain.requirement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QuoteVerifierTest {

	private static final String DOCUMENT = "Users can  book\na table.\tAdmins VIEW reports.";

	@ParameterizedTest(name = "{0} = {1}")
	@CsvSource({
			"'Users can book a table', true",
			"'users CAN book   a table', true",
			"'admins view reports', true",
			"'Users can cancel a table', false",
			"'book a tables', false",
			"'   ', false",
			"'', false" })
	void quoteVerificationIgnoresCaseAndWhitespaceOnly(String quote, boolean expected) {
		assertThat(QuoteVerifier.isVerified(quote, DOCUMENT)).isEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource(nullValues = "NULL", value = { "NULL" })
	void missingQuoteIsNotVerified(String quote) {
		assertThat(QuoteVerifier.isVerified(quote, DOCUMENT)).isFalse();
	}

}
