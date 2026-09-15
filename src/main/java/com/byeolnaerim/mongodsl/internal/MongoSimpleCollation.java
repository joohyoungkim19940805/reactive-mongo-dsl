package com.byeolnaerim.mongodsl.internal;


import java.nio.charset.StandardCharsets;
import java.util.Arrays;


/** MongoDB simple collation compares unsigned UTF-8 bytes, not Java UTF-16 code units. */
public final class MongoSimpleCollation {

	private MongoSimpleCollation() {}

	public static int compare(
		String left, String right
	) {
		// Fast allocation-free path for the common ASCII/BMP case. Supplementary characters
		// and malformed surrogate input go through the same UTF-8 encoding as BSON strings.
		int common = Math.min( left.length(), right.length() );
		for (int index = 0; index < common; index++) {
			char a = left.charAt( index );
			char b = right.charAt( index );
			if (Character.isSurrogate( a ) || Character.isSurrogate( b ))
				return Arrays.compareUnsigned( left.getBytes( StandardCharsets.UTF_8 ), right.getBytes( StandardCharsets.UTF_8 ) );
			if (a != b)
				return Character.compare( a, b );
		}
		return Integer.compare( left.length(), right.length() );
	}

}
