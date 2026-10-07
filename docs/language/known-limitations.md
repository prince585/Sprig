# Known limitations — v0.7.1-beta.1

This list describes the Java stage-0 implementation, not every feature proposed
by the historical design kit in `docs/history/design-kit/`.

- The compiler is written in Java and emits Java source before invoking `javac`.
  It does not compile itself and is not self-hosted.
- `conform C to J` is v1-scoped: Java interfaces only, a non-generic source
  class and target interface, no overloaded abstract methods and no method
  renaming or adapters. It declares a foreign JVM contract; it does not add
  inheritance or interfaces to the language. Generic methods witness by
  erasure; boxed `Short`/`Byte`/`Character` parameters are not expressible
  because of the existing interop adapters.
- Generics accept one or more parameters (`generic K, V:`) but are fully
  explicit: no inference, no variance, and partial type arguments are never
  guessed. A type parameter `T` has no operators or methods, equality only
  under `requires T: Equatable` and ordering only under
  `requires T: Comparable` (Int, Int32, Float, Float32, Decimal, BigInt and
  String). User-defined capabilities are not implemented
  (`SPR-GENERIC-CONSTRAINT`).
- Generic code is erased and boxed in generated Java (type parameters become
  `Object`). Boxing/unboxing is compiler-controlled, but generic values carry
  no JVM-level type information at runtime.
- Local/Git Sprig and Maven JVM dependencies are resolved by `sprig resolve`,
  `sprig add` and `sprig remove`.
  Schema-4 locks verify graph identity, manifests, owner-relative locators and
  JAR/POM SHA-256. Shared
  project classpaths work for check/build/run/api/doctor; only explicit
  dependency-resolution commands use Maven networking. Apache Resolver handles effective POMs and mediation.
  Missing/invalid POMs fail. Publishing/registry, authentication, Maven plugins
  and non-JAR runtime artifacts remain unsupported. See `DEPENDENCIES.md`.
- The small `std/` slice covers UTF-8 filesystem/path, arguments/environment,
  text/time and a typed JSON model. The first-party `sprig-http` library adds a
  small synchronous JDK HTTP client; it does not provide an HTTP server,
  streaming, async requests or a stable package registry. The ecosystem remains
  intentionally small and experimental.
  Debugger integration and incremental compilation are absent. `sprig lsp`
  serves diagnostics, hover, navigation, completion, formatting, local
  rename and quick fixes over the Language Server Protocol; it re-checks the
  whole program on each change, renames only locals and parameters, and has
  no code actions besides those quick fixes, which apply a diagnostic's
  suggested edit (see [language server](../tooling/lsp.md)). The VS Code
  extension uses it when the compiler provides it, and runs checks, programs,
  tests and Java viewing as CLI commands; see `editors/vscode/README.md`.
- JVM interop covers common imported classes, constructors, fields, method
  calls, overloads, and checked exceptions. Java arrays cross the boundary as
  opaque values (no source array syntax). A varargs parameter takes the
  trailing arguments or one opaque array of its element class; a
  type-variable element (`T...`) stays unsupported. Concrete
  generic arguments are preserved for explicit `Type[Arg]` application on
  imported classes and methods; inference, recursive and intersection bounds,
  generic arrays and Short/Byte/Character generic arguments are rejected with
  structured reasons; a wildcard keeps its bound (reads at the upper bound, no
  writes through `? extends`, no wildcard syntax in Sprig); class bounds are validated,
  raw evidence never promotes to concrete arguments, and raw boundaries stay
  erased. Collection conversion is explicit through `@std/jvm.spr`; there is no
  implicit Java/Sprig collection conversion. Java reference results are
  conservatively nullable and reference parameters non-null, except where a
  nullability annotation visible at run time says otherwise (`NotNull`,
  `NonNull`, `Nonnull`, `Nullable`, `CheckForNull`, and the `NullMarked`,
  `NonNullApi` and `MethodsReturnNonnullByDefault` defaults), read through
  reflection or, for annotations kept only in class files such as
  `org.jetbrains.annotations`, from the class file; a `toString()` result is
  non-null.
- Sprig `throws` and `catch` are implemented, but their relationship to Java
  exception classes and top-level execution remains provisional. Checked Java
  exceptions follow Java's rule in one direction more: a catch nothing can
  reach and a declared exception the body cannot throw are both errors. Sprig
  shows an `Error` as its message; Java code that turns one into text, such as
  `String.valueOf` or a Java collection's `toString()`, sees
  `sprig.runtime.SprigError: message`.
- Lambdas have single-expression bodies and support arities zero through
  three. A lambda's `throws Error` comes from its body; it cannot be written
  on the lambda, and a lambda cannot call a function that throws a checked
  Java exception.
- Runtime numeric failures report `SPR-RUNTIME-EXCEPTION` with the nearest
  statement range for local and imported `Int`/`Int32` checked arithmetic, plus
  `data.origin="checked-arithmetic"`; the span is the statement, not a
  sub-expression. JVM library operations do not inherit Sprig's checked integer
  arithmetic rules.
- Generated Java shares the JVM class-file limits. Long string literals are
  split automatically, but one generated method still holds at most 64 KB of
  bytecode: a very large literal collection or a very long top-level script can
  fail with `SPR-JVM-COMPILE` and a hint to load the data from a file or split
  the code into functions. Top-level statements run once, as one JVM method that
  HotSpot compiles while it runs; a long script with several hot loops shares
  one compilation and its inlining budget, so keep hot loops in functions.
- Initialization order is checked rather than inferred: top-level code may not
  use a binding declared below it (`SPR-NAME-FORWARD-REFERENCE`), and a binding
  reached through a function before its initializer ran fails at runtime with
  `data.origin="init-order"`. The check is conservative and cannot prove at
  compile time that such a call never happens.
- Floating-point operations follow Java `float`/`double` behavior. The compiler
  does not promise cross-JVM bitwise identity for transcendental functions,
  numerical stability, physical units, or mathematically correct algorithms.
- Compiler classes use `javac --release 17`. Supported release platforms are Linux/macOS
  with JDK 17 and 26; Windows is an experimental, non-blocking preview; definitions are not execution evidence. The current
  release validation report records which exact source/archive gates ran.
  No production or architecture-wide portability guarantee is made.
- Portable local locks carry owner-relative locators and survive relocation of the
  whole workspace; absolute-path declarations are not portable and need `resolve`
  after the target moves. The lock does not attest source bytes or symlink targets.
  It also does not pin bundled `@std` bytes: `@std` comes from the installed SDK,
  whose published archive is checked separately through release checksums and
  extracted-archive smoke tests.
  Manifest semantic errors can point to line 1. Cache tree verification adds IO;
  OS locks have no timeout. Git submodules are unsupported. Offline Git builds
  require Git and a complete verified cache. Concurrent hostile mutation after
  validation is outside the cooperative cache model.
- `sprig fmt` is canonical and comment-preserving, without configuration or
  aggressive wrapping. See [formatter](../tooling/formatter.md) for file safety and
  comment indentation policy. Editors can request the same formatting through
  `sprig lsp`.
- `sprig test` is a sequential project runner for ordinary `.spr` programs.
  It has a fixed 30-second runtime timeout and no parallel execution, watch
  mode, coverage, snapshots or test-function discovery. It requires a current
  lockfile and checks negative fixtures by diagnostic code, not message. See
  [testing](../tooling/testing.md) for its JSON and temporary directory contracts.
- `tests/agent_eval` provides deterministic fixtures and scoring only. No LLM
  run has been performed, and no model performance is claimed.
- The managed SDK installer/upgrader is supported only on Linux/macOS and
  targets public GitHub releases; on Windows `sprig upgrade` refuses and the
  extracted release ZIP is replaced by hand. Published SHA-256 assets detect
  archive mismatch but do not provide signed provenance. Older SDKs are retained.
  SQLite migrations record filenames without content digests; changing an
  applied migration is unsupported by convention, not automatically detected.
  Migration SQL is trusted project code.
- On Windows, `java.exe` reads its command line in the ANSI code page, so a
  command-line argument outside that code page, such as a source path or program
  argument with an emoji, or with Chinese text on a Western European system, is
  replaced before any Sprig code runs. `bin\sprig.cmd`
  forwards correctly quoted arguments unchanged, including under inherited
  delayed expansion; quoting for `cmd.exe` remains the caller's job. A
  Windows console shows output in its own code page, while redirected output
  is UTF-8 as on Linux and macOS. `print` ends lines with the JVM line
  separator, CRLF on Windows, and JSON `programOutput` reports those bytes.
- The stage-1 frontend is a subset probe, not a self-hosted compiler.
- Sprig targets v0.7.1-beta.1, an experimental Beta under Apache-2.0 (`LICENSE`, `NOTICE`),
  not a production stability or numerical correctness guarantee.

## Callable boundary

Source function types now use `fn(A) -> R`, arities 0–3. Parameters and results
are invariant. A callable type may declare `throws Error` and nothing else: a
lambda that calls a function throwing `Error` has that clause, a value without
the clause is accepted where the clause is expected (not the reverse), and a
`rethrows` function throws exactly what its callable arguments throw. A checked
Java exception never crosses a function value; handle it inside a named
function and call that from the lambda. A Java functional-interface
parameter accepts a Sprig function value without a throws clause: parameters
match exactly after the Java mapping, `void` accepts any result, and the
compiler emits the adapter; abstract classes and generic interface methods are
not converted. For `Fn0..Fn3`, only concrete generic signatures
preserve callable types; raw, wildcard or unresolved type variables are rejected.
`Character`/`Short`/`Byte` callable slots and arbitrary parameterized Java slots
are unsupported because they would require additional erased-value adapters.
Nullable callable values use `(fn(A) -> R)?`; `fn(A) -> R?` means nullable result.
