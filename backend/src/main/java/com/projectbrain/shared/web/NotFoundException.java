package com.projectbrain.shared.web;

public class NotFoundException extends RuntimeException {

	public NotFoundException(String detail) {
		super(detail);
	}

}
