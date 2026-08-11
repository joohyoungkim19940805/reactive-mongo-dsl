package com.byeolnaerim.mongodsl.query;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.bson.Document;

/** Lightweight BSON sort definition used by the DSL. */
public final class MongoSort {

    public enum Direction { ASC, DESC }

    public static final class Order {
        private final Direction direction;
        private final String property;

        private Order(Direction direction, String property) {
            this.direction = Objects.requireNonNull(direction, "direction must not be null");
            this.property = Objects.requireNonNull(property, "property must not be null");
        }

        public static Order asc(String property) { return new Order(Direction.ASC, property); }
        public static Order desc(String property) { return new Order(Direction.DESC, property); }
        public Direction getDirection() { return direction; }
        public String getProperty() { return property; }
        public boolean isAscending() { return direction == Direction.ASC; }
    }

    private static final MongoSort UNSORTED = new MongoSort(List.of());
    private final List<Order> orders;

    private MongoSort(List<Order> orders) {
        this.orders = List.copyOf(orders);
    }

    public static MongoSort unsorted() { return UNSORTED; }
    public static MongoSort by(Order... orders) { return by(Arrays.asList(orders)); }
    public static MongoSort by(Collection<Order> orders) { return new MongoSort(List.copyOf(orders)); }
    public static MongoSort by(Direction direction, String property) { return new MongoSort(List.of(new Order(direction, property))); }
    public boolean isSorted() { return !orders.isEmpty(); }
    public boolean isUnsorted() { return orders.isEmpty(); }
    public List<Order> orders() { return orders; }

    public Document toDocument() {
        Document document = new Document();
        orders.forEach(order -> document.append(order.getProperty(), order.isAscending() ? 1 : -1));
        return document;
    }
}
