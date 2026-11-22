package com.projectbrain.shared.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

	@ExceptionHandler(NotFoundException.class)
	ResponseEntity<Object> notFound(NotFoundException ex) {
		return problem(404, ex.getMessage());
	}

	@ExceptionHandler(ForbiddenException.class)
	ResponseEntity<Object> forbidden(ForbiddenException ex) {
		return problem(403, ex.getMessage());
	}

	@ExceptionHandler(ConflictException.class)
	ResponseEntity<Object> conflict(ConflictException ex) {
		return problem(409, ex.getMessage());
	}

	@ExceptionHandler(UnprocessableException.class)
	ResponseEntity<Object> unprocessable(UnprocessableException ex) {
		return problem(422, ex.getMessage());
	}

	/** Handled here because the stateless error dispatch would otherwise turn a 500 into a 401. */
	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> unexpected(Exception ex) {
		log.error("Unhandled request failure", ex);
		return problem(500, "An unexpected error occurred.");
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		Map<String, List<String>> errors = new LinkedHashMap<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			errors.computeIfAbsent(error.getField(), field -> new ArrayList<>()).add(error.getDefaultMessage());
		}
		ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(422), "Validation failed.");
		body.setProperty("errors", errors);
		return respond(body);
	}

	@Override
	protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		if (ex instanceof MethodArgumentTypeMismatchException mismatch
				&& mismatch.getParameter().hasParameterAnnotation(PathVariable.class)) {
			return problem(404, "Resource not found.");
		}
		return problem(422, "A request parameter has an invalid value.");
	}

	private static ResponseEntity<Object> problem(int status, String detail) {
		return respond(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail));
	}

	private static ResponseEntity<Object> respond(ProblemDetail body) {
		return ResponseEntity.status(body.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
	}

}
