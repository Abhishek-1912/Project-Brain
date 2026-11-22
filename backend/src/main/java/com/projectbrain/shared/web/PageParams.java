package com.projectbrain.shared.web;

public record PageParams(int page, int size) {

	private static final int MAX_SIZE = 100;

	public static PageParams of(int page, int size) {
		if (page < 0) {
			throw new UnprocessableException("page must be 0 or greater.");
		}
		if (size < 1) {
			throw new UnprocessableException("size must be 1 or greater.");
		}
		return new PageParams(page, Math.min(size, MAX_SIZE));
	}

	public long offset() {
		return (long) page * size;
	}

}
