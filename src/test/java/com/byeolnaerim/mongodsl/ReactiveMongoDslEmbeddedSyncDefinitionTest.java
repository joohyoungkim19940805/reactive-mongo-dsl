package com.byeolnaerim.mongodsl;


import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import org.junit.jupiter.api.Test;


class ReactiveMongoDslEmbeddedSyncDefinitionTest {

	// 서로 독립적인 embedded sync 관계는 source/target 방향이 서로 반대여도 cycle로 오인하지 않는지 검증한다.
	@Test
	void unrelatedRelationsMayPointInOppositeDirections() {

		EmbeddedSyncConfig<String> sync = new EmbeddedSyncConfig<>();
		assertDoesNotThrow( () -> sync
			.forKeys( "db" )
				.from( B.class )
				.into( A.class, "b" )
				.build()
			.forKeys( "db" )
				.from( D.class )
				.into( C.class, "d" )
				.build()
		);

	}

	// embedded sync 정의에서 직접적인 directed cycle을 등록 단계에서 거부하는지 검증한다.
	@Test
	void directedCycleIsRejectedAtRegistrationTime() {

		EmbeddedSyncConfig<String> sync = new EmbeddedSyncConfig<>();
		sync.forKeys( "db" ).from( B.class ).into( A.class, "b" ).build();
		assertThrows(
			IllegalStateException.class,
			() -> sync.forKeys( "db" ).from( A.class ).into( B.class, "a" ).build()
		);

	}

	// embedded sync link builder가 양쪽 연결 필드를 명확히 지정하고 target collection cardinality를 자동 추론하는지 검증한다.
	@Test
	void linkBuilderNamesBothSidesExplicitlyAndCollectionCardinalityIsInferred() {

		EmbeddedSyncConfig<String> sync = new EmbeddedSyncConfig<>();
		assertDoesNotThrow( () -> sync
			.forKeys( "db" )
				.from( Child.class )
				.into( Parent.class, "children" )
				.linkBy()
					.fromField( "parentId" )
					.intoField( "id" )
					.end()
				.build()
		);

	}

	private static final class A {
		private B b;
	}

	private static final class B {
		private A a;
	}

	private static final class C {
		private D d;
	}

	private static final class D {}

	private static final class Parent {
		private List<Child> children;
	}

	private static final class Child {
		private String parentId;
	}

}
