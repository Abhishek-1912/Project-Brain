package com.projectbrain.shared.web;

public class UnprocessableException extends RuntimeException {

	public UnprocessableException(String detail) {
		super(detail);
	}

}
