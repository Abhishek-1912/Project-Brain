package com.projectbrain.shared.web;

public class ForbiddenException extends RuntimeException {

	public ForbiddenException(String detail) {
		super(detail);
	}

}
