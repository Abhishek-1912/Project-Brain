package com.projectbrain.shared.web;

public class ServiceUnavailableException extends RuntimeException {

	public ServiceUnavailableException(String detail) {
		super(detail);
	}

}
