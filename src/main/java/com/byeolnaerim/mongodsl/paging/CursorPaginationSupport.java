package com.byeolnaerim.mongodsl.paging;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import com.byeolnaerim.mongodsl.internal.MongoBsonSupport;
import com.mongodb.client.model.Filters;


/** Internal helpers shared by find and lookup cursor-page terminals. */
public final class CursorPaginationSupport {

	private CursorPaginationSupport() {}

	/** Generates a deterministic opaque token id for one stored keyset position. */
	public static String tokenId(
		String queryKey, int pageSize, Document sortValues
	) {

		return fingerprint( "cursor-token-v1", queryKey, pageSize, sortValues );

	}

	/** Returns whether a client-supplied token has the exact format issued by this library. */
	public static boolean isTokenId(
		String token
	) {

		if (token == null || token.length() != 64)
			return false;
		for (int i = 0; i < token.length(); i++) {
			char c = token.charAt( i );
			if (! ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
				return false;

		}
		return true;

	}

	public static Optional<Document> normalizeSort(
		Bson sort
	) {

		Document normalized = sort == null ? new Document( "_id", -1 ) : MongoBsonSupport.toDocument( sort );
		for (Map.Entry<String, Object> entry : normalized.entrySet()) {
			if (! (entry.getValue() instanceof Number number) || (number.intValue() != 1 && number.intValue() != -1))
				return Optional.empty();

		}
		if (! normalized.containsKey( "_id" ))
			normalized.append( "_id", -1 );
		return Optional.of( normalized );

	}

	public static Optional<Document> anchorValues(
		Document row, Document sort
	) {

		Document values = new Document();
		for (String field : sort.keySet()) {
			PathValue pathValue = readPath( row, field );
			if (! pathValue.present())
				return Optional.empty();
			values.put( field, pathValue.value() );

		}
		return Optional.of( values );

	}

	public static Bson atOrAfterAnchor(
		Document sort, Document values
	) {

		List<Bson> alternatives = new ArrayList<>();
		List<Bson> prefix = new ArrayList<>();
		int index = 0;

		for (Map.Entry<String, Object> entry : sort.entrySet()) {
			String field = entry.getKey();
			int direction = ((Number) entry.getValue()).intValue();
			Object anchorValue = values.get( field );
			boolean last = ++index == sort.size();
			List<Bson> current = new ArrayList<>( prefix );
			current.add(
				direction > 0
					? (last ? Filters.gte( field, anchorValue ) : Filters.gt( field, anchorValue ))
					: (last ? Filters.lte( field, anchorValue ) : Filters.lt( field, anchorValue ))
			);
			alternatives.add( current.size() == 1 ? current.get( 0 ) : Filters.and( current ) );
			prefix.add( Filters.eq( field, anchorValue ) );

		}

		return alternatives.size() == 1 ? alternatives.get( 0 ) : Filters.or( alternatives );

	}

	public static Bson combine(
		Bson base, Bson cursor
	) {

		Document baseDocument = MongoBsonSupport.toDocument( base );
		return baseDocument.isEmpty() ? cursor : Filters.and( base, cursor );

	}

	public static String fingerprint(
		Object... parts
	) {

		try {
			MessageDigest digest = MessageDigest.getInstance( "SHA-256" );
			for (Object part : parts) {
				byte[] bytes = String.valueOf( part ).getBytes( StandardCharsets.UTF_8 );
				digest.update( bytes );
				digest.update( (byte) 0 );

			}
			return HexFormat.of().formatHex( digest.digest() );

		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException( "SHA-256 is unavailable", e );

		}

	}

	private static PathValue readPath(
		Document document, String path
	) {

		Object current = document;
		String[] segments = path.split( "\\." );

		for (String segment : segments) {
			if (! (current instanceof Document currentDocument) || ! currentDocument.containsKey( segment ))
				return new PathValue( false, null );
			current = currentDocument.get( segment );

		}
		return new PathValue( true, current );

	}

	private record PathValue(boolean present, Object value) {}

}
