package com.byeolnaerim.mongodsl;


import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.byeolnaerim.mongodsl.internal.MongoFieldNameSupport;
import com.byeolnaerim.mongodsl.sync.EmbeddedDeletePolicy;
import com.byeolnaerim.mongodsl.sync.EmbeddedSyncDefinition;
import com.byeolnaerim.mongodsl.sync.EmbeddedSyncDefinition.LinkFieldPair;
import com.byeolnaerim.mongodsl.sync.EmbeddedSyncLeaseStore;


/**
 * Standalone embedded-snapshot synchronization configuration injected into {@link ReactiveMongoDsl}.
 * Relations are directed ({@code from -> into}); unrelated relations may point in any direction,
 * while directed cycles and multiple owners for the same target path are rejected.
 */
public final class EmbeddedSyncConfig<K> {

	record Registration<K>(List<K> keys, EmbeddedSyncDefinition definition) {}

	private final EmbeddedSyncLeaseStore leaseStore;

	private final List<Registration<K>> registrations = new ArrayList<>();

	private final Object graphLock = new Object();

	private final Map<K, Map<Class<?>, Set<Class<?>>>> classGraphs = new HashMap<>();

	private final Map<K, Map<String, Class<?>>> targetOwners = new HashMap<>();

	private final Map<K, Map<String, EmbeddedSyncDefinition>> targetDefinitions = new HashMap<>();

	public EmbeddedSyncConfig() {

		this.leaseStore = null;

	}

	public EmbeddedSyncConfig(
		EmbeddedSyncLeaseStore leaseStore
	) {

		this.leaseStore = Objects.requireNonNull( leaseStore, "leaseStore must not be null" );

	}

	/** Starts one embedded relation definition for one or more resolver keys. */
	@SafeVarargs
	public final FromBuilder forKeys(
		K... keys
	) {

		if (keys == null || keys.length == 0)
			throw new IllegalArgumentException( "forKeys requires at least one resolver key" );
		List<K> values = Arrays
			.stream( keys )
			.map( key -> Objects.requireNonNull( key, "EmbeddedSync resolver key must not be null" ) )
			.distinct()
			.toList();
		return new FromBuilder( values );

	}

	List<Registration<K>> registrations() {

		synchronized (graphLock) {
			return List.copyOf( registrations );

		}

	}

	EmbeddedSyncLeaseStore leaseStoreOr(
		EmbeddedSyncLeaseStore defaultStore
	) {

		return leaseStore == null ? Objects.requireNonNull( defaultStore, "defaultStore must not be null" ) : leaseStore;

	}

	EmbeddedSyncLeaseStore leaseStoreOverride() { return leaseStore; }

	public final class FromBuilder {

		private final List<K> keys;

		private FromBuilder(
			List<K> keys
		) {

			this.keys = keys;

		}

		public <S> IntoBuilder<S> from(
			Class<S> sourceClass
		) {

			return new IntoBuilder<>( keys, Objects.requireNonNull( sourceClass, "sourceClass must not be null" ) );

		}

	}

	public final class IntoBuilder<S> {

		private final List<K> keys;

		private final Class<S> sourceClass;

		private IntoBuilder(
			List<K> keys, Class<S> sourceClass
		) {

			this.keys = keys;
			this.sourceClass = sourceClass;

		}

		public <T> RelationBuilder<S, T> into(
			Class<T> targetClass
		) {

			return new RelationBuilder<>( keys, sourceClass, targetClass, null );

		}

		public <T> RelationBuilder<S, T> into(
			Class<T> targetClass, String targetField
		) {

			return new RelationBuilder<>( keys, sourceClass, targetClass, targetField );

		}

	}

	public final class RelationBuilder<S, T> {

		private final List<K> keys;

		private final Class<S> sourceClass;

		private final Class<T> targetClass;

		private final String targetField;

		private final List<LinkFieldPair> links = new ArrayList<>();

		private String mapKeyField;

		private EmbeddedDeletePolicy deletePolicy = EmbeddedDeletePolicy.REMOVE;

		private RelationBuilder(
			List<K> keys, Class<S> sourceClass, Class<T> targetClass, String targetField
		) {

			this.keys = keys;
			this.sourceClass = sourceClass;
			this.targetClass = Objects.requireNonNull( targetClass, "targetClass must not be null" );
			this.targetField = targetField;

		}

		public LinkBuilder<S, T> linkBy() {

			return new LinkBuilder<>( this );

		}

		public RelationBuilder<S, T> mapKey(
			String fromField
		) {

			this.mapKeyField = MongoFieldNameSupport.toMongoField( Objects.requireNonNull( fromField, "fromField must not be null" ) );
			return this;

		}

		public RelationBuilder<S, T> onDelete(
			EmbeddedDeletePolicy deletePolicy
		) {

			this.deletePolicy = Objects.requireNonNull( deletePolicy, "deletePolicy must not be null" );
			return this;

		}

		public EmbeddedSyncConfig<K> build() {

			EmbeddedSyncDefinition definition = EmbeddedSyncDefinition.create(
				sourceClass,
				targetClass,
				targetField,
				links,
				mapKeyField,
				deletePolicy
			);
			validateAndRegister( keys, definition );
			return EmbeddedSyncConfig.this;

		}

	}

	public final class LinkBuilder<S, T> {

		private final RelationBuilder<S, T> parent;

		private String pendingFromField;

		private LinkBuilder(
			RelationBuilder<S, T> parent
		) {

			this.parent = parent;

		}

		public LinkBuilder<S, T> fromField(
			String fromField
		) {

			if (pendingFromField != null)
				throw new IllegalStateException( "The previous fromField requires intoField before another fromField." );
			this.pendingFromField = MongoFieldNameSupport.toMongoField( Objects.requireNonNull( fromField, "fromField must not be null" ) );
			return this;

		}

		public LinkBuilder<S, T> intoField(
			String intoField
		) {

			if (pendingFromField == null)
				throw new IllegalStateException( "fromField must be specified before intoField." );
			String rawIntoField = Objects.requireNonNull( intoField, "intoField must not be null" );
			boolean intoIdAlias = Arrays.stream( rawIntoField.split( "\\.", -1 ) ).anyMatch( "id"::equals );
			parent.links.add( new LinkFieldPair( pendingFromField, MongoFieldNameSupport.toMongoField( rawIntoField ), intoIdAlias ) );
			pendingFromField = null;
			return this;

		}

		public RelationBuilder<S, T> end() {

			if (pendingFromField != null)
				throw new IllegalStateException( "fromField requires a matching intoField." );
			if (parent.links.isEmpty())
				throw new IllegalStateException( "linkBy requires at least one fromField/intoField pair." );
			return parent;

		}

	}

	private void validateAndRegister(
		List<K> keys, EmbeddedSyncDefinition definition
	) {

		synchronized (graphLock) {
			for (K key : keys) {
				Map<Class<?>, Set<Class<?>>> graph = classGraphs.computeIfAbsent( key, ignored -> new HashMap<>() );
				Map<String, Class<?>> owners = targetOwners.computeIfAbsent( key, ignored -> new HashMap<>() );
				String targetPath = definition.targetClass().getName() + "#" + definition.targetField();
				Class<?> owner = owners.get( targetPath );
				if (owner != null && owner != definition.sourceClass())
					throw new IllegalStateException(
						"Embedded synchronization target already has another source: " + definition.targetClass().getName() + "." + definition.targetField()
					);
				EmbeddedSyncDefinition existingDefinition = targetDefinitions
					.computeIfAbsent( key, ignored -> new HashMap<>() )
					.get( targetPath );
				if (existingDefinition != null && ! existingDefinition.equals( definition ))
					throw new IllegalStateException(
						"Embedded synchronization target already has a different definition: " + definition.targetClass().getName() + "." + definition.targetField()
					);
				if (definition.sourceClass() == definition.targetClass() || hasClassPath( graph, definition.targetClass(), definition.sourceClass() ))
					throw new IllegalStateException(
						"Embedded synchronization cycle detected: " + definition.sourceClass().getName() + " -> " + definition.targetClass().getName()
					);

			}

			for (K key : keys) {
				classGraphs.computeIfAbsent( key, ignored -> new HashMap<>() )
					.computeIfAbsent( definition.sourceClass(), ignored -> new HashSet<>() )
					.add( definition.targetClass() );
				String targetPath = definition.targetClass().getName() + "#" + definition.targetField();
				targetOwners.computeIfAbsent( key, ignored -> new HashMap<>() ).put( targetPath, definition.sourceClass() );
				targetDefinitions.computeIfAbsent( key, ignored -> new HashMap<>() ).put( targetPath, definition );

			}
			Registration<K> registration = new Registration<>( List.copyOf( keys ), definition );
			if (! registrations.contains( registration ))
				registrations.add( registration );

		}

	}

	private boolean hasClassPath(
		Map<Class<?>, Set<Class<?>>> graph, Class<?> start, Class<?> target
	) {

		Deque<Class<?>> queue = new ArrayDeque<>();
		Set<Class<?>> visited = new HashSet<>();
		queue.push( start );
		while (! queue.isEmpty()) {
			Class<?> current = queue.pop();
			if (! visited.add( current ))
				continue;
			if (current == target)
				return true;
			for (Class<?> next : graph.getOrDefault( current, Set.of() ))
				queue.push( next );

		}
		return false;

	}

}
