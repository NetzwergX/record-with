# record-with

Type-safe derived record creation for Java — no new language constructs required.

This library provides `with`, `transform`, and batched `draft` operations for
Java records using nothing but method references, generics, and default methods
on interfaces.  It is a working implementation of an idea first explored in
[Record#with — a thought experiment](https://sebastian.teumert.net/blog/record-with-a-thought-experiment-2020)
(June 2020), updated with computed transformations and batched derivation.

```java
public record Person(String name, int age) implements Copyable<Person> {}

var duke = new Person("Duke", 28);

var duchess  = duke.with(Person::name, "Duchess");
var older = duke.transform(Person::age, (old, self) -> old + 1);

var other = duke.draft()
    .set(Person::name, "Mocha")
    .transform(Person::age, (old, self) -> self.name().length() * 2)
    .construct();
```

Requires Java 17+.  Zero runtime dependencies.


## Why this exists

JDK Enhancement Proposal 468 ("Derived Record Creation") proposes adding a
`with` keyword to the Java language for creating modified copies of records.
As of mid-2026, JEP 468 remains in Candidate status — it has not been targeted
to any JDK release.

This library is a counter-proposal by example.  It demonstrates that derived
record creation does not require language changes at all: the existing type system
is expressive enough to support the feature as a library, with stronger
compile-time guarantees than the proposed language extension.


## API

The library provides three mixin interfaces at increasing levels of capability,
plus a convenience super-interface that combines all three.

### Withable — simple value replacement

```java
public record Point(int x, int y) implements Withable<Point> {}

var p = new Point(1, 2);
var p2 = p.with(Point::x, 10);           // Point[x=10, y=2]
var p3 = p.with(Point::x, 10)
          .with(Point::y, 20);            // Point[x=10, y=20]
```

### Transformable — computed component derivation

The transformer receives two arguments: `old` (the current component value)
and `self` (the unmodified record).

```java
public record Point(int x, int y) implements Transformable<Point> {}

var p = new Point(3, 7);
var doubled = p.transform(Point::x, (old, self) -> old * 2);
var swapped = p.transform(Point::x, (old, self) -> self.y());
```

### Draftable — batched multi-field derivation

When a record's canonical constructor enforces cross-component constraints,
chained `with` calls may fail on intermediate states.  The `Draft` builder
collects all changes and invokes the constructor exactly once.

```java
public record Bounded(int lo, int hi) implements Draftable<Bounded> {
    public Bounded {
        if (lo >= hi) throw new IllegalArgumentException(
            "lo must be < hi");
    }
}

var b = new Bounded(1, 10);

// Chaining would fail — with(lo, 15) creates Bounded(15, 10), rejected.
// Draft applies both before calling the constructor:
var b2 = b.draft()
          .set(Bounded::lo, 15)
          .set(Bounded::hi, 20)
          .construct();                   // Bounded[lo=15, hi=20]
```

All transformers registered on a draft see the *original* record.  There is no
order-dependence between `set` and `transform` calls.

```java
// Swap x and y in a single constructor call:
var swapped = point.draft()
    .transform(Point::x, (old, self) -> self.y())
    .transform(Point::y, (old, self) -> self.x())
    .construct();
```

### Copyable — all of the above

```java
public record Person(String name, int age) implements Copyable<Person> {}
```

`Copyable<R>` extends `Withable<R>`, `Transformable<R>`, and `Draftable<R>`.
Use it when you want the full API without thinking about granularity.


## The case against JEP 468

JEP 468 proposes a new expression form with a new contextual keyword (`with`),
implicit variable declarations, and a transformation block that can contain
arbitrary code:

```java
// JEP 468 syntax
Point result = p with { x = 0; };
Point scaled = p with { x *= 2; y = x + z; };
```

This library achieves the same result without any language changes:

```java
// Library syntax
Point result = p.with(Point::x, 0);
Point scaled = p.draft()
    .transform(Point::x, (old, self) -> old * 2)
    .transform(Point::y, (old, self) -> self.x() * 2 + self.z())
    .construct();
```

The JEP 468 form is shorter.  But brevity is not the only axis that matters
for a language feature, and records — whose entire purpose is predictability —
deserve scrutiny on the axes where the two approaches differ.


### Compile-time safety

Both JEP 468 and this library catch basic errors — wrong component name, wrong
type, wrong record — at compile time.  On these points the guarantees are
equivalent.  The library's advantage lies in a narrower but significant area:
**the absence of implicit scoping** and the **stability guarantees under record
evolution**.

**Wrong component name.** JEP 468's assignment restriction requires the
left-hand side to be either a component variable or a variable declared within
the block.  Assigning to a nonexistent component is a compile error.  The
library achieves the same: `Person::x` does not compile if `Person` has no
`x()` accessor.  **No advantage here — equivalent.**

**Wrong component type.** JEP 468 catches type mismatches because the implicit
variables are typed.  The library catches them through generics: `Person::name`
resolves `T` to `String`, so the value argument must be `String`.
**Equivalent.**

**Wrong record type.** JEP 468 scopes the component namespace to the origin
expression's type.  The library achieves this through `SerializableFunction<R, T>`:
`person.with(Point::x, 5)` is a type error.  **Equivalent.**

**Where the library is strictly safer:** the shadowing and evolution concerns
described in the next section.  JEP 468's implicit variable declarations can
shadow outer variables on *reads*, and adding a component to a record can
silently change the meaning of existing `with` blocks.  The library has no
implicit declarations — `Point::x` is an unambiguous method reference that
cannot be confused with a local variable, and record evolution cannot alter
existing call sites.


### No implicit variable shadowing

JEP 468's transformation block implicitly introduces mutable local variables
for each record component.  These shadow any outer variables with the same
name.  The assignment example is benign — assigning to a non-component name is
a compile error.  The real issue is on **reads**, and specifically on **record
evolution**.

Consider:

```java
int offset = 5;
Point p = origin with { x = offset; };  // offset is the outer variable
```

This works fine today.  But if `Point` later gains a component named `offset`:

```java
record Point(int x, int y, int offset) { }
```

Now `offset` inside the block silently resolves to `origin.offset()` instead
of the outer `int offset = 5`.  The code still compiles.  The behavior changes
without warning.  This is the backward-compatibility concern raised by Attila
Kelemen on the amber-spec-experts mailing list (April 2024).  The JEP authors
acknowledged it as a "Risk and Assumption."

The library has no such risk.  `Point::x` is an unambiguous method reference.
There are no implicit variable declarations, no shadowing, and no scoping
surprises.  Adding a component to a record cannot silently alter the behavior
of existing `with` or `transform` call sites.


### No arbitrary side effects

JEP 468's transformation block can contain arbitrary statements: conditionals,
loops, method calls, assignments to multiple implicit variables.  The JEP
authors explicitly rejected a simpler declarative form
(`e with { x1 = e1; ...; xn = en; }`) as "unnecessarily restrictive."

But records exist specifically to be transparent, predictable data carriers.
Their canonical constructors validate invariants.  Their accessors are expected
to be pure.  Introducing an imperative mutation DSL alongside these constraints
is a philosophical mismatch.

The library forces a clean separation: `with` is a value, `transform` is a
function.  Neither allows unrelated side effects.  The code in a transform
lambda *can* technically call external methods, but the structure makes the
intent explicit — you are computing a replacement value, not running a program.


### No language complexity budget spent

Every new keyword, expression form, and scoping rule added to Java must be
learned by every Java developer, documented in every textbook, handled by every
IDE, and maintained forever.  JEP 468 introduces:

- A new contextual keyword (`with`)
- A new expression type (derived record creation expression)
- Implicit variable declarations with mutable-by-default semantics
- A new scoping rule (component variables shadow outer variables)
- Control flow restrictions (no `break`, `continue`, or `yield` out of the block)

The library introduces nothing.  It uses method references, generics, default
methods, and functional interfaces — all features that have existed since Java 8
(method references, generics, functional interfaces) or Java 16 (records).
A developer who understands these features understands the library.

Java's strength has always been that new capabilities emerge from the type system.
Generics, annotations, lambdas with functional interfaces, sealed classes with
pattern matching — these are all mechanisms that compose with existing constructs.
A library-based `with` stays within that tradition.  A language-level `with`
keyword departs from it.


### Applicable beyond records (in principle)

JEP 468 is explicitly scoped to records only: "It is not a goal to provide
derived creation expressions for ordinary, non-record values."  The library's
mechanism — method references as component selectors, reflective read/construct
— could be adapted for any class that exposes named accessors and a matching
constructor, although the current implementation relies on `RecordComponent`
specifically.  A language change locked to records cannot be retroactively
extended without another language change.


## The case for compiler optimization

The implicit argument behind JEP 468 is that efficient derived record creation
*requires* language-level syntax — that only the compiler, with full syntactic
control, can generate optimal code.  This is not true.  The information needed
to optimize the library pattern already exists at compile time, and Java has
repeatedly followed the model of library-first, compiler-optimizes-later.

**Precedent.** `String` concatenation with `+` was compiled to `StringBuilder`
chains for years.  JEP 280 (JDK 9) replaced that with `invokedynamic` and
`StringConcatFactory` — a runtime optimization that the compiler delegates to.
The language didn't change.  The syntax didn't change.  The compiler just got
smarter about what the existing constructs meant.  `MethodHandle`,
`VarHandle`, and stream pipelines followed the same path: library API first,
JIT intrinsification later.

**What the compiler already knows.** When it sees the call
`point.with(Point::x, 5)`, all the information for optimization is statically
available:

- `Point::x` is a method reference to a record component accessor — the
  component name and index are known at compile time.
- The record type is `Point` — its canonical constructor and component
  layout are known.
- The value `5` is the replacement — its type matches the component type.

A sufficiently motivated `javac` or JIT could desugar this to
`new Point(5, point.y(), point.z())` — the exact same bytecode that JEP 468
would produce.  No new keyword.  No new scoping rules.  No new expression
type.

**A better optimization target than JEP 468.** The transformation block in
JEP 468 can contain arbitrary code: conditionals, loops, side effects.  The
compiler cannot always reduce it to a simple constructor call.  The library's
API, by contrast, is structurally constrained: `with` is always a single field
replacement, `transform` is always a single function application.  The simpler
the pattern, the easier it is to optimize.

**Two levels where this could happen:**

*JIT level.* HotSpot already inlines `MethodHandle.invoke()` when the handle
is a compile-time constant.  With the record metadata cached and the lambda
class stable per call site, the hot path reduces to a cache lookup followed by
a `MethodHandle` chain — all of which HotSpot already knows how to inline.

*Compiler level.* Teach `javac` to recognize calls to
`Withable.with(R::component, value)` where the method reference targets a
record component, and emit optimized bytecode that bypasses the reflective
machinery entirely.  This is analogous to how `javac` replaced `StringBuilder`
chains with `invokedynamic` bootstrap calls for string concatenation.

**The pitch:** instead of teaching Java developers a new keyword, teach the
Java compiler to optimize the patterns they already write.  Express intent
through the type system, let the compiler optimize the execution.  The library
provides the semantics and the compile-time safety guarantees.  The compiler
provides the performance.  No language change required.


## Limitations

This is an honest assessment of where the library approach is weaker than a
language-level feature.

**The `SerializedLambda` hack.**  Extracting the method name from a method
reference via `writeReplace` is an unsupported technique.  It works reliably
with method references but not with arbitrary lambdas, and the JDK team could
break it.  This is the single biggest practical weakness.  The planned migration
path is to Project Babylon's code reflection API (`@CodeReflection`), which
provides the same capability through a supported, standardized mechanism.
Babylon is in active incubation as of 2026 and expected to produce JEPs across
multiple future releases.

**Verbosity for complex multi-field transformations.**  When many components
change with cross-field dependencies, the library form is more verbose than
JEP 468's block form:

```java
// JEP 468
var r = p with { x *= 2; y = x + z; z = 0; };

// Library
var r = p.draft()
    .transform(Point::x, (old, self) -> old * 2)
    .transform(Point::y, (old, self) -> self.x() * 2 + self.z())
    .set(Point::z, 0)
    .construct();
```

Note that in the library form, the `y` transformer must manually replicate
the `x` transformation (`self.x() * 2`) because all transformers see the
original record, not intermediate results.  This is a deliberate design choice
(no order-dependence) but it costs clarity for complex cases.

**Overload ambiguity.**  Java's type inference cannot always disambiguate
`with(accessor, T)` from `with(accessor, BiFunction<T, R, T>)` when lambdas
are involved, particularly with autoboxed primitives.  This is why `with` and
`transform` are separate methods rather than overloads.  The separate names
are arguably clearer — `with` sets, `transform` computes — but they are a
concession to the compiler, not a design preference.

**Accessor-vs-field semantics.**  The library reads component values via
accessor methods, not raw fields.  If an accessor transforms data (e.g.
`public int n() { return 2 * n; }`), unchanged components will be read through
the accessor and passed to the constructor, which may re-transform them.
JEP 468 has the same issue — this is fundamentally a record semantics question,
not a library limitation.

**Performance.**  The first `with` call on a given record class incurs a
one-time cost to build and cache `MethodHandle` chains.  Subsequent calls reuse
the cache and should be competitive with hand-written wither methods, but this
has not been formally benchmarked.  A language-level feature could, in principle,
be optimized more aggressively by the JIT compiler.


## Design

### Interface hierarchy

```
Withable<R>          — with(R::field, value)
Transformable<R>     — transform(R::field, (old, self) -> ...)
Draftable<R>         — draft() → Draft<R>
  └── Draft<R>           .set(R::field, value)
                         .transform(R::field, (old, self) -> ...)
                         .construct() → R
Copyable<R>          — extends all three
```

The interfaces are independent — implement any combination.  `Copyable<R>` is a
convenience that extends all three for users who want the full API.

### Internals

`RecordIntrospector` caches per-class metadata: an array of `MethodHandle`s for
component accessors, a `MethodHandle` for the canonical constructor, and a
name-to-index map.  The cache is a `ConcurrentHashMap` and is built once per
record class.

Method reference resolution uses `SerializedLambda` to extract the
implementation method name, which is matched against the cached component index.
This is the component that will be replaced by Project Babylon's code reflection
in a future version.


## Installation

Published to [GitHub Packages](https://github.com/NetzwergX/record-with/packages).
You need to configure GitHub Packages as a Maven repository and authenticate
with a personal access token (classic) that has `read:packages` scope.

### Maven

Add the repository and dependency to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/NetzwergX/record-with</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>net.teumert</groupId>
        <artifactId>record-with</artifactId>
        <version>0.1.0</version>
    </dependency>
</dependencies>
```

Configure authentication in `~/.m2/settings.xml`:

```xml
<servers>
    <server>
        <id>github</id>
        <username>YOUR_GITHUB_USERNAME</username>
        <password>YOUR_GITHUB_TOKEN</password>
    </server>
</servers>
```

### Gradle

In `build.gradle`:

```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/NetzwergX/record-with")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_USERNAME")
            password = project.findProperty("gpr.token") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation 'net.teumert:record-with:0.1.0'
}
```

Or in `build.gradle.kts`:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/NetzwergX/record-with")
        credentials {
            username = project.findProperty("gpr.user") as String?
                ?: System.getenv("GITHUB_USERNAME")
            password = project.findProperty("gpr.token") as String?
                ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("net.teumert:record-with:0.1.0")
}
```


## Building

```
mvn clean verify
```

Requires Java 17+ and Maven 3.8+.

### Running tests

```
mvn test
```

Tests run against the full API surface: value replacement, computed transforms,
batched drafts, constructor validation, accessor semantics, and error cases
(24 tests total).

### CI

GitHub Actions runs the test suite against Java 17 and 21 on every push to
`main` and on pull requests.  Publishing to GitHub Packages is triggered by
version tags:

```
git tag v0.1.0
git push --tags
```

The workflow strips the `v` prefix, sets the POM version accordingly, and
deploys to GitHub Packages automatically.

### Development environment

The repository includes a [dev container](.devcontainer/devcontainer.json)
configuration for VS Code and GitHub Codespaces.  Open the project in a
container to get a pre-configured Java 21 environment with Maven and the
Java extension pack.


## License

MIT — see source files for details.


## Origin

This library grew out of a
[2020 blog post](https://sebastian.teumert.net/blog/record-with-a-thought-experiment-2020)
that proved `Record#with` could be implemented within Java's existing
type system — no new language constructs needed.  This library takes the
same idea and makes it practical: cached `MethodHandle` chains, computed
transformations, batched derivation, and a granular interface hierarchy.
