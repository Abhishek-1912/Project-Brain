package com.projectbrain.knowledge;

/** Writes a vector in pgvector's text form, which SQL casts with CAST(:value AS extensions.vector). */
final class VectorText {

	private VectorText() {
	}

	static String of(float[] vector) {
		StringBuilder text = new StringBuilder(vector.length * 8).append('[');
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				text.append(',');
			}
			text.append(Float.toString(vector[i]));
		}
		return text.append(']').toString();
	}

}
