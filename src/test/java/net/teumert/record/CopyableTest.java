package net.teumert.record;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CopyableTest {

    // ── Test records ─────────────────────────────────────────────

    public record Point(int x, int y) implements Copyable<Point> {}

    public record Person(String name, int age) implements Copyable<Person> {}

    public record Point3D(double x, double y, double z) implements Copyable<Point3D> {}

    /** Record with constructor validation — x must be less than y. */
    public record Bounded(int lo, int hi) implements Copyable<Bounded> {
        public Bounded {
            if (lo >= hi) throw new IllegalArgumentException(
                    "lo (%d) must be < hi (%d)".formatted(lo, hi));
        }
    }

    /** Record with a reference-type component. */
    public record Tagged(String label, List<String> tags) implements Copyable<Tagged> {}

    /** Record with a custom accessor that transforms data (the 2020 Doubling example). */
    public record Doubling(int n, int m) implements Copyable<Doubling> {
        @Override public int n() { return 2 * n; }
        @Override public int m() { return 2 * m; }
    }

    /** Single-component record. */
    public record Wrapper(String value) implements Copyable<Wrapper> {}


    // ── Eager with (direct value) ────────────────────────────────

    @Nested
    class EagerDirectValue {

        @Test
        void replaces_single_component() {
            var p = new Point(1, 2);
            var p2 = p.with(Point::x, 10);

            assertEquals(10, p2.x());
            assertEquals(2, p2.y());
        }

        @Test
        void original_is_unchanged() {
            var p = new Point(1, 2);
            p.with(Point::x, 99);

            assertEquals(1, p.x());
            assertEquals(2, p.y());
        }

        @Test
        void chaining_multiple_fields() {
            var p = new Point(1, 2);
            var p2 = p.with(Point::x, 10).with(Point::y, 20);

            assertEquals(10, p2.x());
            assertEquals(20, p2.y());
        }

        @Test
        void works_with_string_component() {
            var duke = new Person("Duke", 28);
            var duchess = duke.with(Person::name, "Duchess");

            assertEquals("Duchess", duchess.name());
            assertEquals(28, duchess.age());
        }

        @Test
        void works_with_three_components() {
            var p = new Point3D(1.0, 2.0, 3.0);
            var p2 = p.with(Point3D::z, 99.0);

            assertEquals(1.0, p2.x());
            assertEquals(2.0, p2.y());
            assertEquals(99.0, p2.z());
        }

        @Test
        void allows_null_value() {
            var tagged = new Tagged("hello", List.of("a", "b"));
            var t2 = tagged.with(Tagged::tags, null);

            assertEquals("hello", t2.label());
            assertNull(t2.tags());
        }

        @Test
        void single_component_record() {
            var w = new Wrapper("old");
            var w2 = w.with(Wrapper::value, "new");

            assertEquals("new", w2.value());
        }

        @Test
        void respects_constructor_validation() {
            var b = new Bounded(1, 10);

            // Valid change
            var b2 = b.with(Bounded::lo, 5);
            assertEquals(5, b2.lo());

            // Invalid change — lo >= hi
            assertThrows(RecordCopyException.class,
                    () -> b.with(Bounded::lo, 10));
        }

        @Test
        void identity_copy_via_same_value() {
            var p = new Person("Alice", 30);
            var p2 = p.with(Person::name, "Alice");

            assertEquals(p, p2);
            assertNotSame(p, p2); // different instance
        }
    }


    // ── Eager with (transformer) ─────────────────────────────────

    @Nested
    class EagerTransformer {

        @Test
        void transforms_with_old_value() {
            var p = new Point(5, 10);
            var p2 = p.transform(Point::x, (old, self) -> old * 2);

            assertEquals(10, p2.x());
            assertEquals(10, p2.y());
        }

        @Test
        void transforms_with_record_access() {
            var p = new Point(5, 10);
            // Set x to the value of y
            var p2 = p.transform(Point::x, (old, self) -> self.y());

            assertEquals(10, p2.x());
            assertEquals(10, p2.y());
        }

        @Test
        void swap_via_chained_transform() {
            var p = new Point(3, 7);
            // Capture original y before the first with mutates it
            int origY = p.y();
            var swapped = p.transform(Point::x, (old, self) -> self.y())
                           .transform(Point::y, (old, self) -> p.x());

            assertEquals(7, swapped.x());
            assertEquals(3, swapped.y());
        }

        @Test
        void cross_field_computation_3d() {
            var p = new Point3D(1.0, 2.0, 3.0);
            var p2 = p.transform(Point3D::x, (old, self) -> self.y() + self.z());

            assertEquals(5.0, p2.x());
        }

        @Test
        void string_transformation() {
            var person = new Person("alice", 25);
            var p2 = person.transform(Person::name,
                    (old, self) -> old.substring(0, 1).toUpperCase() + old.substring(1));

            assertEquals("Alice", p2.name());
        }
    }


    // ── Lazy builder ─────────────────────────────────────────────

    @Nested
    class DraftBuilder {

        @Test
        void batched_value_replacement() {
            var p = new Point(1, 2);
            var p2 = p.draft()
                      .set(Point::x, 10)
                      .set(Point::y, 20)
                      .construct();

            assertEquals(10, p2.x());
            assertEquals(20, p2.y());
        }

        @Test
        void batched_avoids_intermediate_validation_failure() {
            // Bounded requires lo < hi.
            // Eager chaining would fail: Bounded(1,10).with(lo,15) -> 15 >= 10 -> boom!
            // Lazy applies both changes before calling the constructor.
            var b = new Bounded(1, 10);

            var b2 = b.draft()
                      .set(Bounded::lo, 15)
                      .set(Bounded::hi, 20)
                      .construct();

            assertEquals(15, b2.lo());
            assertEquals(20, b2.hi());
        }

        @Test
        void batched_with_transformers() {
            var p = new Point(3, 7);
            var p2 = p.draft()
                      .transform(Point::x, (old, self) -> old * 10)
                      .transform(Point::y, (old, self) -> self.x() + self.y())
                      .construct();

            // x: 3 * 10 = 30
            // y: original x (3) + original y (7) = 10
            assertEquals(30, p2.x());
            assertEquals(10, p2.y());
        }

        @Test
        void transformers_see_original_not_other_overrides() {
            // Both transformers must see the original record,
            // regardless of set() call order.
            var p = new Point(5, 10);
            var p2 = p.draft()
                      .transform(Point::x, (old, self) -> self.y())     // should see original y=10
                      .transform(Point::y, (old, self) -> self.x())     // should see original x=5
                      .construct();

            // Effectively a swap
            assertEquals(10, p2.x());
            assertEquals(5, p2.y());
        }

        @Test
        void mix_of_values_and_transformers() {
            var person = new Person("alice", 25);
            var p2 = person.draft()
                           .set(Person::name, "Bob")
                           .transform(Person::age, (old, self) -> old + 5)
                           .construct();

            assertEquals("Bob", p2.name());
            assertEquals(30, p2.age());
        }

        @Test
        void no_changes_produces_equal_copy() {
            var p = new Point(1, 2);
            var p2 = p.draft().construct();

            assertEquals(p, p2);
            assertNotSame(p, p2);
        }

        @Test
        void last_set_wins_for_same_component() {
            var p = new Point(1, 2);
            var p2 = p.draft()
                      .set(Point::x, 10)
                      .set(Point::x, 99) // overrides previous
                      .construct();

            assertEquals(99, p2.x());
        }

        @Test
        void lazy_validates_on_construct() {
            var b = new Bounded(1, 10);

            var lazy = b.draft()
                        .set(Bounded::lo, 50);
            // hi is still 10 from original -> 50 >= 10

            assertThrows(RecordCopyException.class, lazy::construct);
        }
    }


    // ── Error cases ──────────────────────────────────────────────

    @Nested
    class ErrorCases {

        @Test
        void nonexistent_component_throws() {
            // Person has 'name' and 'age' — no 'x'
            // We can't easily test this with method references since the
            // compiler enforces the type. But we can test internal resolution.
            var meta = RecordIntrospector.metaFor(Person.class);

            assertThrows(RecordCopyException.class,
                    () -> RecordIntrospector.resolveIndex(meta, "nonexistent"));
        }
    }


    // ── Accessor-vs-field behavior (the 2020 Doubling example) ──

    @Nested
    class AccessorBehavior {

        @Test
        void documents_accessor_doubling_on_copy() {
            // This demonstrates the accessor-vs-field behavior your
            // 2020 blog post identified. Doubling.n() returns 2*n,
            // so reading via the accessor and re-constructing doubles
            // unchanged components on each copy.
            //
            // This is a known, documented consequence of using accessors
            // rather than raw fields. The same issue exists in JEP 468.

            var original = new Doubling(2, 3);

            // Accessor returns 2*n=4, 2*m=6
            assertEquals(4, original.n());
            assertEquals(6, original.m());

            // Copy with n=5: reads m via accessor (2*3=6),
            // passes (5, 6) to constructor -> stored as (5, 6).
            // New accessor returns: n()=10, m()=12
            var copy = original.with(Doubling::n, 5);
            assertEquals(10, copy.n());  // 2 * 5
            assertEquals(12, copy.m());  // 2 * (2*3) = 2 * 6
        }
    }
}
