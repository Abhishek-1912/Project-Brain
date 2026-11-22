package com.projectbrain.shared.web;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ProblemHandler {

	@ExceptionHandler(NotFoundException.class)
	ResponseEntity<ProblemDetail> notFound(NotFoundException ex) {
		return problem(404, ex);
	}

	@ExceptionHandler(ForbiddenException.class)
	ResponseEntity<ProblemDetail> forbidden(ForbiddenException ex) {
		return problem(403, ex);
	}

	@ExceptionHandler(ConflictException.class)
	ResponseEntity<ProblemDetail> conflict(ConflictException ex) {
		return problem(409, ex);
	}

	@ExceptionHandler(UnprocessableException.class)
	ResponseEntity<ProblemDetail> unprocessable(UnprocessableException ex) {
		return problem(422, ex);
	}

	private static ResponseEntity<ProblemDetail> problem(int status, RuntimeException ex) {
		HttpStatusCode code = HttpStatusCode.valueOf(status);
		return ResponseEntity.status(code)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(ProblemDetail.forStatusAndDetail(code, ex.getMessage()));
	}

}
