package com.byeolnaerim.mongodsl.internal;


import java.lang.reflect.Field;
import org.springframework.data.annotation.Id;

public final class MongoIdFieldResolver {

	private MongoIdFieldResolver() {}


	/**
	 * 엔티티 클래스에서 식별자(@Id) 필드를 찾는 메서드.
	 * 
	 * {@code @Id} 어노테이션이 붙은 필드를 우선적으로 찾고, 없을 경우 이름이 "id"인 필드를 찾는다.
	 * 
	 * @param entityClass
	 *            대상 엔티티 클래스
	 * 
	 * @return 식별자 필드
	 * 
	 * @throws IllegalArgumentException
	 *             클래스 계층 내에서 @Id 필드나 이름이 "id"인 필드를 찾지 못한 경우
	 */
	public static Field findIdField(
		Class<?> entityClass
	) {

		// 필드 이름이 "id"인 것을 저장할 변수
		Field idNamedField = null;
		Class<?> currentClass = entityClass;

		// 클래스 계층을 순회하며 필드를 탐색합니다.
		while (currentClass != null && currentClass != Object.class) {

			for (Field field : currentClass.getDeclaredFields()) {

				// @Id 어노테이션이 붙은 필드를 최우선으로 간주하고 즉시 반환합니다.
				if (field.isAnnotationPresent( Id.class )) {
					field.setAccessible( true );
					return field;

				}

				// @Id가 없고, 아직 "id" 필드를 찾지 못했으며, 현재 필드 이름이 "id"인 경우
				// (가장 하위 클래스의 "id" 필드를 저장하기 위해 idNamedField == null 조건 추가)
				if (idNamedField == null && "id".equals( field.getName() )) {
					idNamedField = field;

				}

			}

			// 상위 클래스에서 필드를 계속 찾습니다.
			currentClass = currentClass.getSuperclass();

		}

		// @Id 어노테이션을 찾지 못한 경우, 순회 중 발견했던 "id" 필드가 있는지 확인합니다.
		if (idNamedField != null) {
			idNamedField.setAccessible( true );
			return idNamedField;

		}

		// @Id 어노테이션과 "id" 필드 모두 찾지 못한 경우 예외를 발생시킵니다.
		throw new IllegalArgumentException(
			"No @Id annotation or 'id' field found in class hierarchy for " + entityClass.getName()
		);

	}
}