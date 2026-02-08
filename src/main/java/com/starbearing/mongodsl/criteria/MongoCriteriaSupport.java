package com.starbearing.mongodsl.criteria;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.query.Criteria;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MongoCriteriaSupport {
	private MongoCriteriaSupport() {}

	// 지구 반지름 (meters)
	static final double EARTH_RADIUS_M = 6_378_137.0;
	

	public static <T> Flux<FieldsPair<String, Object>> extractFieldsPairs(
		T entity, String... fieldNames
	) {

		if (entity == null || fieldNames == null || fieldNames.length == 0) { return Flux.error( new IllegalArgumentException( "Entity or fieldNames must not be null or empty." ) ); }

		return Flux
			.fromArray( fieldNames )
			.flatMap( fieldName -> Mono.fromCallable( () -> {
				Field field = entity.getClass().getDeclaredField( fieldName );
				field.setAccessible( true );
				Object value = field.get( entity );
				return new FieldsPair<>( fieldName, value );

			} ) );
		// .onErrorMap( e -> new RuntimeException( "Failed to extract fields: " + e.getMessage(), e ) );

	}

	public static Flux<Criteria> createQueryReactive(
		Collection<FieldsPair<?, ?>> fieldsPairs
	) {


		if (fieldsPairs == null || fieldsPairs.isEmpty()) // { return Mono.error( new IllegalArgumentException( "FieldsPairs must not be null or empty." ) );
			// }
			return Flux.empty();
		return Flux
			.fromIterable( fieldsPairs )
			.flatMap( fieldsPair -> {
				String fieldName;

				if (fieldsPair.getFieldName() instanceof Enum<?> enumValue) {
					fieldName = enumValue.name();

				} else if (fieldsPair.getFieldName() instanceof String stringValue) {
					fieldName = stringValue;

				} else {
					fieldName = fieldsPair.getFieldName().toString();

				}

				Object fieldValue = fieldsPair.getFieldValue();
				FieldsPair.Condition queryType = fieldsPair.getQueryType();

				return Mono
					.just( Criteria.where( fieldName ) )
					.map( criteria -> switch (queryType) {
						case eq -> criteria.is( fieldValue );
						case notEq -> criteria.ne( fieldValue );
						case gt -> criteria.gt( fieldValue );
						case gte -> criteria.gte( fieldValue );
						case lt -> criteria.lt( fieldValue );
						case lte -> criteria.lte( fieldValue );
						case in -> {

							if (fieldValue instanceof Collection<?>) {
								yield criteria.in( (Collection<?>) fieldValue );

							} else {
								throw new IllegalArgumentException( "Field value must be a collection for 'in' query type." );

							}

						}
						case notIn -> {

							if (fieldValue instanceof Collection<?>) {
								yield criteria.nin( (Collection<?>) fieldValue );

							} else {
								throw new IllegalArgumentException( "Field value must be a collection for 'notIn' query type." );

							}

						}
						case like -> criteria.regex( fieldValue.toString(), "i" );
						case regex -> criteria.regex( fieldValue.toString() );
						case exists -> {

							if (fieldValue instanceof Boolean existsValue) {
								yield criteria.exists( existsValue );

							} else {
								throw new IllegalArgumentException( "Field value must be a Boolean for 'exists' query type." );

							}

						}
						case isNull -> criteria.is( null );
						case isNotNull -> criteria.ne( null );
						case between -> {

							if (fieldValue instanceof Collection<?> values && values.size() == 2) {
								Object[] rangeValues = values.toArray();
								yield criteria.gte( rangeValues[0] ).lte( rangeValues[1] );

							} else {
								throw new IllegalArgumentException( "Field value must be a collection of size 2 for 'between' query type." );

							}

						}
						case near -> {

							// ex) 좌표 기준 가까운 5km 검색
							// FieldsPair.of("propertyDetail.location", new Double[]{127.0, 37.0, 5000.0},
							// FieldsPair.Query.near)
							if (fieldValue instanceof Double[] point && point.length >= 3) {
								var near = criteria.near( new Point( point[0], point[1] ) );

								if (point.length == 4) {
									near.maxDistance( point[2] ).minDistance( point[3] );

								} else {
									near.maxDistance( point[2] );

								}

								yield near;

							} else {
								throw new IllegalArgumentException( "Field value must be a collection of size 3 (geo x, geo y, max distance, min distance) for 'near' query type." );

							}

						}
						case elemMatch -> {

							/* 필드 예: FieldsPair.of("propertyDetail", subFieldsPairs, Query.elemMatch)
							 * - 여기서 subFieldsPairs는 Collection<FieldsPair<?,?>> 형태라고 가정. */
							if (fieldValue instanceof Collection<?> subPairs) {
								// subPairs 안에 FieldsPair<?,?> 들이 있다고 가정
								// -> 하위 조건을 Criteria로 만든다 (AND 연산)
								List<Criteria> subCriteriaList = new ArrayList<>();

								for (Object o : subPairs) {

									if (o instanceof FieldsPair<?, ?> sp) {
										// 여기서 createSingleCriteria(sp)를 재사용
										Criteria sc = createSingleCriteria( sp );
										subCriteriaList.add( sc );

									}

								}

								// subCriteriaList를 하나의 Criteria로 합친다(AND)
								// ex) new Criteria().andOperator(subCriteriaList.toArray(new Criteria[0]))
								Criteria subCombined = new Criteria().andOperator( subCriteriaList.toArray( new Criteria[0] ) );

								// 최종 elemMatch
								yield criteria.elemMatch( subCombined );

							} else {
								throw new IllegalArgumentException( "Field value must be a collection of FieldsPair<?,?> for 'elemMatch' query type." );

							}

						}
						default -> throw new IllegalArgumentException( "Unsupported query type: " + queryType );

					} );
				// .onErrorMap( e -> new RuntimeException( "Failed to create Criteria: " + e.getMessage(), e ) );

			} );

	}

	public static Criteria createSingleCriteria(
		FieldsPair<?, ?> pair
	) {

		String fieldName;

		if (pair.getFieldName() instanceof Enum<?>) {
			fieldName = ((Enum<?>) pair.getFieldName()).name();

		} else {
			fieldName = pair.getFieldName().toString();

		}

		Object fieldValue = pair.getFieldValue();
		FieldsPair.Condition queryType = pair.getQueryType();

		try {
			Criteria criteria = Criteria.where( fieldName );

			switch (queryType) {
				case eq:
					return criteria.is( fieldValue );
				case all:
					if (fieldValue instanceof Collection<?>) {
						return criteria.all( (Collection<?>) fieldValue );

					} else {
						throw new IllegalArgumentException( "Field value must be a collection for 'in' query type." );

					}
					// return criteria.all( fieldValue );
				case notEq:
					return criteria.ne( fieldValue );
				case gt:
					return criteria.gt( fieldValue );
				case gte:
					return criteria.gte( fieldValue );
				case lt:
					return criteria.lt( fieldValue );
				case lte:
					return criteria.lte( fieldValue );
				case in:
					if (fieldValue instanceof Collection<?>) {
						return criteria.in( (Collection<?>) fieldValue );

					} else {
						throw new IllegalArgumentException( "Field value must be a collection for 'in' query type." );

					}
				case notIn:
					if (fieldValue instanceof Collection<?>) {
						return criteria.nin( (Collection<?>) fieldValue );

					} else {
						throw new IllegalArgumentException( "Field value must be a collection for 'notIn' query type." );

					}
				case like:
					return criteria.regex( fieldValue.toString(), "i" );
				case regex:
					return criteria.regex( fieldValue.toString() );
				case exists:
					if (fieldValue instanceof Boolean) {
						return criteria.exists( (Boolean) fieldValue );

					} else {
						throw new IllegalArgumentException( "Field value must be a Boolean for 'exists' query type." );

					}
				case isNull:
					return criteria.is( null );
				case isNotNull:
					return criteria.ne( null );
				case between:
					if (fieldValue instanceof Collection<?> values && values.size() == 2) {
						Object[] rangeValues = values.toArray();
						return criteria.gte( rangeValues[0] ).lte( rangeValues[1] );

					} else {
						throw new IllegalArgumentException( "Field value must be a collection of size 2 for 'between' query type." );

					}
				case near:
					if (fieldValue instanceof Double[] point && point.length >= 3) {
						Point location = new Point( point[0], point[1] );
						Criteria nearCriteria = criteria.near( location );

						if (point.length == 4) {
							nearCriteria.maxDistance( point[2] ).minDistance( point[3] );

						} else {
							nearCriteria.maxDistance( point[2] );

						}

						return nearCriteria;

					} else {
						throw new IllegalArgumentException( "Field value must be a Double array with at least 3 elements for 'near' query type." );

					}
				case nearSphere:
					if (fieldValue instanceof Double[] p && p.length >= 3) {
						double lon = p[0], lat = p[1];
						double maxMeters = p[2];

						// 미터를 라디안으로 변환
						double maxRadians = maxMeters / EARTH_RADIUS_M;

						Criteria c = criteria
							.nearSphere( new Point( lon, lat ) )
							.maxDistance( maxRadians );

						if (p.length == 4) {
							double minMeters = p[3];
							double minRadians = minMeters / EARTH_RADIUS_M;
							c.minDistance( minRadians );

						}

						return c;

					} else {
						throw new IllegalArgumentException( "nearSphere requires Double[]{lon,lat,maxMeters[,minMeters]}" );

					}
				case elemMatch:
					/* 예: FieldsPair.of("propertyDetail", List.of(
					 * FieldsPair.of("location", ???, Query.near),
					 * FieldsPair.of("province", "경기도", Query.eq)
					 * ), Query.elemMatch) */
					if (fieldValue instanceof Collection<?> subPairs) {
						// subPairs 안에 FieldsPair<?,?> 들이 들어있다고 가정
						List<Criteria> subCriteriaList = new ArrayList<>();

						for (Object o : subPairs) {

							if (o instanceof FieldsPair<?, ?> sp) {
								// 재귀적으로 Criteria 생성
								Criteria sc = createSingleCriteria( sp );
								subCriteriaList.add( sc );

							}

						}

						// subCriteriaList를 하나로 합치기
						// $elemMatch는 내부적으로 "이 배열 원소 중에서 아래 Criteria들을 모두 만족하는 원소"
						// => typically andOperator
						Criteria subCombined = new Criteria().andOperator( subCriteriaList.toArray( new Criteria[0] ) );
						return criteria.elemMatch( subCombined );

					} else {
						throw new IllegalArgumentException( "Field value must be a collection of FieldsPair<?,?> for 'elemMatch' query type." );

					}

				default:
					throw new IllegalArgumentException( "Unsupported query type: " + queryType );

			}

		} catch (Exception e) {
			throw new RuntimeException( "Failed to create Criteria: " + e.getMessage(), e );

		}

	}

}
