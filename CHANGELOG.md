# Changelog

Release notes for published versions live in [docs/CHANGELOG.md](docs/CHANGELOG.md).
This file additionally records the detailed change rationale for unreleased fixes.

## Unreleased

### Fixed: `@QueryName(encoded = true)` on nullable `List`/`Array` was double-encoded

- **Symptom.** When a `@QueryName(encoded = true)` parameter was a nullable
  `List`/`Array`, already-encoded names were appended to the regular
  `parameters` builder instead of `encodedParameters`. Ktor then percent-encoded
  them again: `100%25done` became `100%2525done`, `a%2Bb` became `a%252Bb`, a
  literal `+` became `%2B`, and a plain Unicode name such as `名前` was escaped
  to `%E5%90%8D%E5%89%8D`.
- **Structural choice.** KSP query generation already splits three axes in
  `getQueryNameText` (`ktorfit-ksp/src/main/kotlin/de/jensklingenberg/ktorfit/reqBuilderExtension/QueryCodeGeneration.kt`):
  scalar vs collection (`List`/`Array`), and encoded vs not encoded. The scalar
  branch selected `encodedParameters` vs `parameters` correctly; the shared
  collection branch returned `"parameters"` in **both** arms of its
  `if (encoded)` expression. The fix changes only the `encoded == true` arm of
  that single branch to `"encodedParameters"`. No parallel implementation, no
  new abstraction, and no value-based special-casing were introduced.
- **Pre-existing coverage gap.** Existing KSP tests covered scalar
  `@QueryName` and the `@Query` collection branch (which already selected the
  builder from `encoded`), but `@QueryName` had no collection test at all and
  no test anywhere executed a generated `@QueryName` implementation against an
  engine, so the wrong builder could not be observed at runtime.

### Regression cases and the branch/invariant each one pins

Each behavior below is asserted twice against the *same-shaped* interface: once
on the exact generated statement in
`ktorfit-ksp/src/test/kotlin/de/jensklingenberg/ktorfit/QueryAnnotationsTest.kt`
(`whenEncodedQueryNameCollectionFoundUseEncodedParameters`, extracting the
per-function `url{}` block so a statement from one method cannot satisfy the
assertion of another), and once on the URL the engine receives in
`ktorfit-lib-core/src/jvmTest/kotlin/de/jensklingenberg/ktorfit/QueryNameEncodedIntegrationTest.kt`.

| Invariant | Generated-source assertion | MockEngine URL case |
| --- | --- | --- |
| Nullable `List` + encoded appends to `encodedParameters`; null collection is skipped (`names?.filterNotNull()` receiver intact) | `encodedListBlock` contains `encodedParameters.appendAll("$it", emptyList())` and no `parameters.appendAll` | `encodedNullListAddsNoQuery` → `http://localhost/posts` |
| Pre-encoded name survives untouched (the primary `%`/`+` hazard) | same `encodedListBlock` statement | `encodedListWithPreEncodedNamesIsNotEncodedAgain` → `...?a%2Bb&c+d`; `encodedListWithPercentIsNotEncodedAgain` → `...?100%25done` |
| Null members are dropped, neighbors kept | `filterNotNull()` token in the block | `encodedListWithNullMembersSkipsOnlyTheNulls` → `...?a%2Bb` |
| Empty-string member is still a name-only entry | collection statement iterates per member (no special case for `""`) | `encodedListWithEmptyStringMemberIsKept` → `http://localhost/posts?` |
| Plain Unicode name is not re-escaped on the encoded path | same `encodedParameters.appendAll` statement | `encodedListWithUnicodeNameIsPassedThrough` → `...?名前` |
| Nullable `Array` shares the collection branch and must be fixed identically, with null collection skipped and null members dropped | `encodedArrayBlock` contains `encodedParameters.appendAll("$it", emptyList())` | `encodedNullArrayAddsNoQuery` → no query; `encodedArrayWithPreEncodedNamesIsNotEncodedAgain` → `...?a%2Bb&c+d` |
| `encoded = false` collections intentionally keep re-encoding | `notEncodedListBlock` contains `parameters.appendAll(...)` and no `encodedParameters` | `notEncodedListStillGetsEncoded` → `...?a%252Bb&%E5%90%8D%E5%89%8D` |
| Scalar encoded path is unchanged (was already correct) | `encodedScalarBlock` contains `encodedParameters.appendAll("$name", emptyList())` with no null-safe wrapper | `encodedScalarRemainsPassthrough` → `...?100%25done` |

The most dangerous counterexample is `100%25done` on a nullable list: under the
old code the request failed not with an error but with a valid-looking,
silently different URL (`100%2525done`), which a return-value-only test would
never catch. Asserting the generated statement and the engine-observed URL for
the same method is what ties the codegen branch to the wire invariant.

### Public behavior deliberately preserved

- Scalar `@QueryName(encoded = true)` keeps emitting
  `encodedParameters.appendAll("$name", emptyList())`; the scalar branch is
  untouched.
- `@QueryName(encoded = false)` (the default) for scalars, `List`, and `Array`
  keeps appending to `parameters`, i.e. values are still percent-encoded.
- `@Query` and `@QueryMap` codegen (including their encoded/not-encoded builder
  selection) is unchanged.
- Nullable collection receivers still short-circuit (`names?.filterNotNull()`),
  null elements are still filtered, and `QueryName` values still serialize as
  name-only parameters via `appendAll(name, emptyList())`.
- Error types, messages, and KSP validation paths are unchanged; no test
  fixture name or sample literal is special-cased.
