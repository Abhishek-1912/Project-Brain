package com.projectbrain.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

class ProblemHandlerTest {

	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
		.setControllerAdvice(new ProblemHandler())
		.build();

	@ParameterizedTest
	@CsvSource({ "not-found,404", "forbidden,403", "conflict,409", "unprocessable,422" })
	void mapsExceptionToProblemDetails(String kind, int expectedStatus) throws Exception {
		mvc.perform(get("/throw/" + kind))
			.andExpect(status().is(expectedStatus))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(expectedStatus))
			.andExpect(jsonPath("$.title").isNotEmpty())
			.andExpect(jsonPath("$.detail").value("detail for " + kind));
	}

	@RestController
	static class ThrowingController {

		@GetMapping("/throw/{kind}")
		void raise(@PathVariable String kind) {
			String detail = "detail for " + kind;
			switch (kind) {
				case "not-found" -> throw new NotFoundException(detail);
				case "forbidden" -> throw new ForbiddenException(detail);
				case "conflict" -> throw new ConflictException(detail);
				default -> throw new UnprocessableException(detail);
			}
		}

	}

}
