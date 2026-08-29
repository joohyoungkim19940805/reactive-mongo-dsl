package com.byeolnaerim.mongodsl;


import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import org.junit.jupiter.api.Test;


class EmbeddedSyncConfigTest {

	// 서로 연관되지 않은 embedded sync 관계는 전체 그래프 방향이 서로 반대여도 독립적으로 등록 가능한지 검증한다.
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

	// 여러 source/target 관계가 섞인 복잡한 그래프라도 실제 방향 순환이 없는 DAG이면 정상 등록되는지 검증한다.
	@Test
	void complexDirectedAcyclicGraphIsAllowed() {

		EmbeddedSyncConfig<String> sync = new EmbeddedSyncConfig<>();
		assertDoesNotThrow( () -> sync
			.forKeys( "db" ).from( D.class ).into( C.class, "d" ).build()
			.forKeys( "db" ).from( C.class ).into( B.class, "c" ).build()
			.forKeys( "db" ).from( B.class ).into( A.class, "b" ).build()
			.forKeys( "db" ).from( D.class ).into( A.class, "d" ).build()
		);

	}

	// 직접적인 양방향 embedded sync 순환 관계를 등록 단계에서 즉시 거부하는지 검증한다.
	@Test
	void directedCycleIsRejectedAtRegistrationTime() {

		EmbeddedSyncConfig<String> sync = new EmbeddedSyncConfig<>();
		sync.forKeys( "db" ).from( B.class ).into( A.class, "b" ).build();
		assertThrows(
			IllegalStateException.class,
			() -> sync.forKeys( "db" ).from( A.class ).into( B.class, "a" ).build()
		);

	}

	// 여러 관계를 거쳐 다시 원점으로 돌아오는 간접 directed cycle도 등록 단계에서 거부하는지 검증한다.
	@Test
	void indirectDirectedCycleIsRejectedAtRegistrationTime() {

		EmbeddedSyncConfig<String> sync = new EmbeddedSyncConfig<>();
		sync.forKeys( "db" ).from( C.class ).into( B.class, "c" ).build();
		sync.forKeys( "db" ).from( B.class ).into( A.class, "b" ).build();
		assertThrows(
			IllegalStateException.class,
			() -> sync.forKeys( "db" ).from( A.class ).into( C.class, "a" ).build()
		);

	}

	// 하나의 target embedded field path에 서로 다른 source owner를 중복 등록하지 못하도록 방어하는지 검증한다.
	@Test
	void sameTargetPathCannotHaveDifferentSourceOwners() {

		EmbeddedSyncConfig<String> sync = new EmbeddedSyncConfig<>();
		sync.forKeys( "db" ).from( B.class ).into( MultiOwnerTarget.class, "snapshot" ).build();
		assertThrows(
			IllegalStateException.class,
			() -> sync.forKeys( "db" ).from( C.class ).into( MultiOwnerTarget.class, "snapshot" ).build()
		);

	}

	// linkBy에서 source/target 연결 필드를 명시하고 target collection 필드 타입으로 다건 cardinality를 추론하는지 검증한다.
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
		private D d;
	}

	private static final class B {
		private A a;
		private C c;
	}

	private static final class C {
		private D d;
		private A a;
	}

	private static final class D {}

	private static final class Parent {
		private List<Child> children;
	}

	private static final class Child {
		private String parentId;
	}

	private static final class MultiOwnerTarget {
		private Object snapshot;
	}

}
