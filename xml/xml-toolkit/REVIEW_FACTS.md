# xml-toolkit — Factual Review

Prepared by reading the source in `xml-toolkit/src/` and running the built jar. No code was changed.
All file/line references are in `xml-toolkit/src/main/java/com/project/toolkit/`.

**Deviation from the original request:** section 3 was specified as `--count 1000` per XSD. On this
machine that measured ~0.4–0.5 files/sec for the two largest schemas (seev.035/seev.036), i.e. a
projected ~35–40 min for those two alone. The user explicitly chose to reduce this to `--count 200`
per XSD (same `--size mixed --seed 42`) to keep the review timely. This is noted wherever it matters.

---

## 1. Build and libraries

| Item | Value |
|---|---|
| Maven | Apache Maven 3.9.9, running on JDK 24.0.2 (Oracle) |
| `java` on PATH (runs the built jar) | 19.0.1 (2022-10-18), HotSpot 64-Bit |
| `maven.compiler.release` (parent `pom.xml`) | 17 |

**Parent `pom.xml`** (`com.project:message-platform:1.0.0`, `packaging=pom`): declares no
`<dependencies>` — it only lists `<module>xml-toolkit</module>` and the compiler-release property.

**`xml-toolkit/pom.xml`** full `<dependencies>`:

| groupId | artifactId | version | scope |
|---|---|---|---|
| org.apache.ws.xmlschema | xmlschema-core | 2.3.1 | compile |
| org.junit.jupiter | junit-jupiter | 5.10.2 | test |

`mvn dependency:tree` shows `xmlschema-core:2.3.1` has **zero transitive dependencies** — no
Woodstox, no Aalto, no stax2-api. The junit-jupiter test dependency pulls in the usual
junit-jupiter-api/params/engine, opentest4j, junit-platform-commons/engine, apiguardian-api (all
test scope only).

**StAX implementation actually used at runtime.** Confirmed by loading the built fat jar
(`xml-toolkit/target/xml-toolkit.jar`, produced by `maven-shade-plugin`) on the classpath and
calling the factory methods directly:

```
XMLInputFactory.newFactory()   = com.sun.xml.internal.stream.XMLInputFactoryImpl
XMLInputFactory.newInstance()  = com.sun.xml.internal.stream.XMLInputFactoryImpl
XMLOutputFactory.newFactory()  = com.sun.xml.internal.stream.XMLOutputFactoryImpl
XMLOutputFactory.newInstance() = com.sun.xml.internal.stream.XMLOutputFactoryImpl
```

This is the **JDK's built-in StAX implementation** (`com.sun.xml.internal.stream`), not Woodstox or
Aalto — consistent with the dependency tree having no StAX implementation jar on the classpath.
Both `GenerateCommand` and `XmlStructureScanner` call `XMLOutputFactory.newInstance()` /
`XMLInputFactory.newInstance()` with no vendor-specific property set (`XmlStructureScanner.java:30`
sets only `IS_COALESCING`), so there is no code path that could pick a different implementation
even if one were on the classpath.

---

## 2. Generate mode: how it works

| Responsibility | Class (file) |
|---|---|
| Parse XSD, find root `Document` element | `SchemaIndex` (`generate/SchemaIndex.java`) |
| Resolve a simple type's restriction chain to its built-in base + merged facets | `SimpleTypeResolver` (`generate/SimpleTypeResolver.java`) → `ResolvedSimpleType` |
| Walk the type tree and stream XML via StAX | `XmlInstanceGenerator` (`generate/XmlInstanceGenerator.java`) |
| Produce leaf text values | `ValueGenerator` (`generate/ValueGenerator.java`) |
| Sample strings matching an `xs:pattern` | `XsdPatternSampler` (`generate/XsdPatternSampler.java`) |
| Orchestrate per-tier calibration, per-file retry loop, CLI options/results | `GenerateCommand` / `GenerateOptions` / `GenerateResult` (`generate/`) |
| Validate a written file against the XSD | `XsdValidatorCache` (`validate/XsdValidatorCache.java`), shared with infer mode |

**Reuse of expensive objects** — created once per run, not per file:
- `XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();` — `GenerateCommand.java:40`, created once before the per-tier calibration loop and passed into every `writeOne`/`trialSize` call for the whole run.
- The compiled `javax.xml.validation.Schema` is cached in a `ConcurrentHashMap<Path, Schema>` keyed by XSD path (`XsdValidatorCache.java:21,27`) — compiled once the first time a given XSD path is validated against, reused for every subsequent file (and reused again by infer mode's post-validation step).
- The repeat factor needed to hit a size tier's byte target is calibrated once per `(XSD, tier)` pair per run (`GenerateCommand.java:45-48`), not recalculated per file.
- `SchemaIndex` (the parsed `XmlSchemaCollection`) is built once per run and shared by every file write (`GenerateCommand.java:33`, passed into `XmlInstanceGenerator`).
- Not reused across files: a fresh `SimpleTypeResolver` and `ValueGenerator` are constructed per `XmlInstanceGenerator` instance, i.e. per file write and per calibration trial (`XmlInstanceGenerator.java:38-39`). `SimpleTypeResolver` has its own internal cache, but that cache does not survive past one file/trial.

**Threading.** Single-threaded. `GenerateCommand.run` is one sequential `for` loop over the file
plan (`GenerateCommand.java:54`); no `ExecutorService`, `Thread`, or parallel stream appears
anywhere in `generate/` or `validate/`.

**Pattern (`xs:pattern`) sampling.** Custom hand-rolled sampler, not a regex library
(`XsdPatternSampler.java`). It parses the pattern into a small AST (alternation → concat →
quantified → atom) and samples from that tree.

| Supported | Not supported |
|---|---|
| Literals | Anchors `^` `$` (parsed as plain literal characters, not treated specially) |
| Grouping `( ... )` | Backreferences |
| Alternation `\|` | Lookahead / lookbehind |
| Quantifiers `?` `*` `+` `{n}` `{n,m}` `{n,}` | Non-greedy quantifiers (`*?`, `+?`, `??`) |
| Character classes `[...]`, ranges `a-z`, negation `[^...]` | Unicode property escapes (`\p{L}` etc.) |
| Shorthands `\d` `\w` (and `\s` mapped to a literal space) | `\D`, `\W`, `\S` (negated shorthands fall through to `shorthand()`'s default branch, which treats the escaped letter as a literal character, e.g. `\S` samples as the literal character `S`) |
| `.` (mapped to `[A-Za-z0-9]`, not "any character") | — |

For unquantified `*`/`+`, the sampler picks a bounded random repeat count (`*` → 0–5, `+` → 1–5,
`{n,}` → `n`–`n+5`) rather than an unbounded one (`Quantified` record, lines 59-85). Negated
character classes `[^...]` sample from a fixed alphanumeric fallback set and reject matches found
in the excluded ranges, retrying up to 20 times (lines 216-233). For non-negated classes that mix
letters/digits with other characters (e.g. typical SWIFT "x"-charset patterns), it biases 85% of
samples toward the alphanumeric-only subset of the ranges (`alnumRanges()`, line 209) so output
reads as words rather than punctuation noise — but the other ~15% of the time it samples the full
declared class including punctuation. This is visible in the generated output (section 3) as
occasional values like `pp00tQDw7/L` or `61S'Z7uUf3yd2't1o`.

**`xs:any` handling.** `writeAnyFiller()` (`XmlInstanceGenerator.java:189-193`) always writes the
exact same fixed stub, regardless of the `xs:any`'s `namespace`/`processContents` attributes:
```xml
<ExtensionData>supplementary-data</ExtensionData>
```
In `seev.035` and `seev.036`, the only `xs:any` in each schema (`namespace="##any"
processContents="lax"`) sits inside `complexType SupplementaryDataEnvelope1`, which is the type of
element `Envlp`, which is itself the sole child of the repeatable `SplmtryData` element
(`SplmtryData` → `Envlp` → `xs:any`). So generated output always contains:
```xml
<ns:SplmtryData><ns:Envlp><ExtensionData>supplementary-data</ExtensionData></ns:Envlp></ns:SplmtryData>
```
(confirmed in the sample shown in section 3). `ExtensionData` is written with no namespace prefix
(the ambient default/no namespace), which the schema's `namespace="##any"` permits.

**`xs:choice` / `xs:all` / nillable / default/fixed / attributes / namespaces:**
- `xs:choice` (`writeChoice`, lines 173-187): if the choice itself is optional (`minOccurs==0`), it is included ~70% of the time (`OPTIONAL_INCLUDE_PROBABILITY = 0.70`, same constant used for optional elements/attributes). When included, it repeats `repeatCountFor(minOccurs, maxOccurs)` times, and **each repetition independently picks one branch uniformly at random** (`items.get(rnd.nextInt(items.size()))`) — no weighting toward branches already used.
- `xs:all` (lines 149-152): walked identically to `xs:sequence`, i.e. children written in declared order via the same `writeParticleMember`. None of the 4 checked-in XSDs actually declare an `xs:all` (`grep -c "<xs:all"` = 0 in all four), so this path is unexercised by the real schemas.
- **Nillable, `default`, `fixed`**: none of these are read anywhere in the generate code — there is no call to `isNillable()`, `getDefaultValue()`, or `getFixedValue()` in the module (`grep` across `src/main` found zero matches). A required element is always written with a freshly generated value even if the schema declares a `fixed` value for it. (None of the 4 checked-in XSDs use `nillable`, `default=`, or `fixed=` either, so this gap is not exercised by the real schemas, but it is a real gap in the code.)
- Attributes (`writeAttributes`, lines 125-142): required attributes always written; optional attributes included with the same 70% probability; value generated via the same `SimpleTypeResolver`/`ValueGenerator` path as elements.
- Namespaces: a single fixed prefix `"ns"` is bound once on the root element (`writer.writeNamespace(NS_PREFIX, namespace)`, called only when `declareNamespace=true`, i.e. only for the root call in `writeDocument`); every element thereafter is written with `writer.writeStartElement("ns", localName, namespace)` using the schema's single target namespace. Attributes are written unqualified (`writer.writeAttribute(attr.getName(), ...)`, no namespace argument). StAX "repairing namespaces" mode is deliberately not used (code comment, `XmlInstanceGenerator.java:54-58`) specifically so that the same seed produces byte-identical output run to run.

**Streaming vs in-memory.** Real file writes go straight to a file `OutputStream`
(`Files.newOutputStream(outFile)`, `GenerateCommand.java:158`) wrapped in a StAX
`XMLStreamWriter` — the XML is streamed out element-by-element, not built as a DOM or string in
memory first. The only in-memory buffering in the module is during size-tier *calibration*, which
writes a few throwaway trial documents to a capped in-memory `ByteArrayOutputStream`
(`BoundedByteArrayOutputStream`, lines 171-185) purely to measure their size before picking a
repeat factor; those trial documents are discarded, never written to disk.

---

## 3. Generate mode: real run results

Run as: `java -jar xml-toolkit/target/xml-toolkit.jar generate --xsd <xsd> --out <dir> --count 200 --size mixed --seed 42`
(count reduced from the requested 1000 to 200 per the note at the top of this report).

| XSD | Total | Valid 1st try or after retry | Retries needed | Failed after 5 attempts | Small/Med/Large count | Total time | Files/sec |
|---|---|---|---|---|---|---|---|
| seev.035 (seev_035_001_16) | 200 | 200 | not exposed by tool output* | 0 | 67/67/66 | 473.84 s | 0.4 |
| seev.036 (seev_036_001_16) | 200 | 200 | not exposed by tool output* | 0 | 67/67/66 | 377.88 s | 0.5 |
| supl.009 (CAPA_Extension) | 200 | 200 | not exposed by tool output* | 0 | 67/67/66 | 76.02 s | 2.6 |
| supl.011 (CACO_Extension) | 200 | 200 | not exposed by tool output* | 0 | 67/67/66 | 106.35 s | 1.9 |

\* `GenerateResult` only accumulates `validCount`/`invalidCount`/`totalBytes`/`elapsedMillis`
(`GenerateResult.java:10-14`); the per-file retry loop in `GenerateCommand.run` (lines 60-68) does
not record how many of the 1..5 attempts actually succeeded, and nothing is printed for a file that
fails once then succeeds on retry — only a file that **still** fails after all 5 attempts gets a
`[WARN]` line (line 76). So "needed retries" cannot be determined from the tool's own output; it
would require instrumenting the code, which this review does not do.

**Validation errors / failures:** zero. Across all 800 files generated in this run (4 XSDs × 200
files), `invalid=0` in every run and no `[WARN]` lines appeared in any of the four run logs. There
are no validation error messages to report for section 3 — every file validated against its XSD,
either on the first attempt or a retry.

**File sizes per tier (KB), from the 200-file runs above:**

| XSD | Tier | n | Min (KB) | Avg (KB) | Max (KB) |
|---|---|---|---|---|---|
| seev.035 | small | 67 | 1.39 | 4.14 | 7.93 |
| seev.035 | medium | 67 | 1.40 | 28.11 | 56.85 |
| seev.035 | large | 66 | 0.98 | 1373.70 | 2520.14 |
| seev.036 | small | 67 | 2.34 | 4.40 | 6.24 |
| seev.036 | medium | 67 | 4.06 | 24.33 | 40.90 |
| seev.036 | large | 66 | 15.50 | 893.34 | 1382.45 |
| supl.009 | small | 67 | 0.75 | 1.66 | 2.56 |
| supl.009 | medium | 67 | 14.98 | 34.97 | 48.85 |
| supl.009 | large | 66 | 192.44 | 416.01 | 604.71 |
| supl.011 | small | 67 | 0.60 | 2.01 | 3.02 |
| supl.011 | medium | 67 | 9.08 | 32.80 | 49.52 |
| supl.011 | large | 66 | 0.50 | 392.27 | 578.63 |

Notable fact from these numbers: the `large` tier targets ~977 KB (`SizeTier.LARGE = 1_000_000`
bytes), but seev.035's large-tier min is 0.98 KB and supl.011's large-tier min is 0.50 KB —
essentially the same size as a `small` file. This matches the behavior documented in the
project's own README ("deeply-nested `xs:choice` schemas can by chance skip whole rich sub-trees"):
seev.035 and supl.011 both have many `xs:choice` points (88 and 8 respectively, see section 1's
element counts), so an unlucky run of choice outcomes can skip most optional content even when the
repeat factor was calibrated for a 1 MB median.

**One small generated XML, `seev.035`, 1426 bytes (`Document_small_130.xml`), full content:**

```xml
<?xml version="1.0" encoding="UTF-8"?><ns:Document xmlns:ns="urn:iso:std:iso:20022:tech:xsd:seev.035.001.16"><ns:CorpActnMvmntPrlimryAdvc><ns:Pgntn><ns:PgNb>477</ns:PgNb><ns:LastPgInd>false</ns:LastPgInd></ns:Pgntn><ns:MvmntPrlimryAdvcGnlInf><ns:MvmntPrlimryAdvcId>pp00tQDw7/L</ns:MvmntPrlimryAdvcId><ns:Tp>NEWM</ns:Tp><ns:Fctn>CAPA</ns:Fctn></ns:MvmntPrlimryAdvcGnlInf><ns:PrvsMvmntPrlimryAdvcId><ns:Id>hsgX0/4</ns:Id></ns:PrvsMvmntPrlimryAdvcId><ns:RvslRsn><ns:Rsn><ns:Cd>IPRI</ns:Cd></ns:Rsn></ns:RvslRsn><ns:CorpActnGnlInf><ns:CorpActnEvtId>,WMzY3'5o3B</ns:CorpActnEvtId><ns:EvtTp><ns:Cd>EXRI</ns:Cd></ns:EvtTp><ns:MndtryVlntryEvtTp><ns:Cd>CHOS</ns:Cd></ns:MndtryVlntryEvtTp><ns:UndrlygScty><ns:FinInstrmId><ns:ISIN>AXJ3I02S5433</ns:ISIN><ns:Desc>3507,h6VJSKJ8Vw </ns:Desc></ns:FinInstrmId></ns:UndrlygScty></ns:CorpActnGnlInf><ns:AcctDtls><ns:AcctsListAndBalDtls><ns:SfkpgAcct>61S'Z7uUf3yd2't1o</ns:SfkpgAcct></ns:AcctsListAndBalDtls></ns:AcctDtls><ns:CorpActnDtls><ns:SctiesQty><ns:BaseDnmtn><ns:Cd>UKWN</ns:Cd></ns:BaseDnmtn><ns:IncrmtlDnmtn><ns:Cd>UKWN</ns:Cd></ns:IncrmtlDnmtn></ns:SctiesQty><ns:EvtStag><ns:Cd>FULL</ns:Cd></ns:EvtStag><ns:IntrmdtSctiesDstrbtnTp><ns:Cd>EXRI</ns:Cd></ns:IntrmdtSctiesDstrbtnTp><ns:LtryTp><ns:Cd>ORIG</ns:Cd></ns:LtryTp></ns:CorpActnDtls><ns:SplmtryData><ns:Envlp><ExtensionData>supplementary-data</ExtensionData></ns:Envlp></ns:SplmtryData></ns:CorpActnMvmntPrlimryAdvc></ns:Document>
```

Note the values like `pp00tQDw7/L`, `hsgX0/4`, `,WMzY3'5o3B`, `61S'Z7uUf3yd2't1o` — these come from
`XsdPatternSampler` sampling the full declared character class (including punctuation) on the
~15%-of-the-time branch described in section 2, for fields whose `xs:pattern` permits punctuation.

---

## 4. Infer mode: how it works and real run results

| Responsibility | Class |
|---|---|
| Parse CLI options | `InferOptions` |
| Read every sample XML with StAX, merge into one tree | `XmlStructureScanner` → `ElementNode` |
| Guess a leaf's XSD datatype from its observed text samples | `DatatypeGuesser` |
| Render the merged tree as an XSD | `XsdWriter` |
| Render the plain-language review report | `ReviewReportWriter` |
| Orchestrate: scan → write XSD → write report → validate inputs against the new XSD | `InferCommand` |

**Namespace handling.** `XsdWriter.write()` reads the root element's namespace URI
(`root.qname.getNamespaceURI()`); if non-empty, it emits `targetNamespace="<ns>"` and
`elementFormDefault="qualified"` on the generated `<xs:schema>`, and prefixes every reference to a
generated named type with `tns:` (`XsdWriter.java:25-40`). Confirmed in the real run below — the
inferred schema's header is:
```xml
<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:iso:std:iso:20022:tech:xsd:seev.035.001.16" targetNamespace="urn:iso:std:iso:20022:tech:xsd:seev.035.001.16" elementFormDefault="qualified">
```

**Real run: `infer` on 50 generated `seev.035` samples** (the first 50, by filename sort order, of
the 200 valid `seev.035` files from section 3 — i.e. real generator output, not hand-crafted):

```
java -jar xml-toolkit/target/xml-toolkit.jar infer --xml <50-file-dir> --out seev035_inferred.xsd
```

| | Original XSD | Inferred XSD |
|---|---|---|
| Total `<xs:element>` declarations (incl. reused refs) | 227 | 340 |
| Distinct element local names | 65 | 143 |
| `<xs:complexType>` count | 95 | 183 |

(The original reuses a small number of named complexTypes — e.g. shared amount/currency/code
types — across many different parent contexts, so its distinct-name count is much lower than its
tag count. The inferred XSD creates a separate complexType per element position the way `XsdWriter`
walks the tree — it does not detect or merge structurally-identical types that occur under
different parents, so both its tag count and complexType count come out higher than the original
despite describing a smaller observed vocabulary of 143 vs. 65 names.)

**Validation of the 50 samples against the XSD inferred from themselves: 1 / 50 valid (2%).**
49 of 50 failed, all with `cvc-complex-type.2.4.a` ("Invalid content was found starting with
element X, one of Y is expected" — a child-element-order mismatch). This is the direct, observed
consequence of the documented "cannot detect `xs:choice`" gap (next subsection): `seev.035`'s root
content model is built from a long chain of `xs:choice` groups, so different sample files legally
contain the alternatives in different presence/absence combinations. `XsdWriter` has no concept of
choice — it always emits a single `xs:sequence` using the order elements were first encountered
across all 50 samples, with `minOccurs="0"` on anything not present in every sample. Any sample
whose actual (valid, schema-permitted) element order or combination differs from that one
first-seen ordering fails strict sequence validation. Top 5 (by first occurrence) error messages
observed, with the element that triggered each:

| # | Element causing it | Message |
|---|---|---|
| 1 | `MvmntPrlimryAdvcId` | `cvc-complex-type.2.4.a: Invalid content was found starting with element '...MvmntPrlimryAdvcId'. One of '...Tp' is expected.` |
| 2 | `Pgntn` | `cvc-complex-type.2.4.a: Invalid content was found starting with element '...Pgntn'. One of '...MvmntPrlimryAdvcGnlInf' is expected.` |
| 3 | `RvslRsn` | `cvc-complex-type.2.4.a: Invalid content was found starting with element '...RvslRsn'. One of '...CorpActnGnlInf' is expected.` |

(Only these 3 distinct messages occurred across the 49 failures — repeated many times each, since
every failing file hits one of these three order mismatches at the very first divergence point in
the document.)

**First 30 lines of the review report** (`seev035_inferred_review.md`):

```
# XSD inference review

Based on 50 sample files.

## Optional fields detected

- Document/CorpActnMvmntPrlimryAdvc/MvmntPrlimryAdvcGnlInf/MvmntPrlimryAdvcId: in 38 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/PrvsMvmntPrlimryAdvcId: in 33 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/PrvsMvmntPrlimryAdvcId/Id: in 33 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/MvmntConfId: in 35 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/MvmntConfId/Id: in 35 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/CorpActnGnlInf/UndrlygScty/FinInstrmId/ISIN: in 31 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/CorpActnGnlInf/UndrlygScty/FinInstrmId/OthrId: in 32 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/CorpActnGnlInf/UndrlygScty/FinInstrmId/OthrId/Id: in 32 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/CorpActnGnlInf/UndrlygScty/FinInstrmId/OthrId/Tp: in 32 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/CorpActnGnlInf/UndrlygScty/FinInstrmId/OthrId/Tp/Cd: in 16 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/CorpActnGnlInf/UndrlygScty/FinInstrmId/OthrId/Tp/Prtry: in 16 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/CorpActnGnlInf/UndrlygScty/FinInstrmId/Desc: in 29 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/CorpActnGnlInf/OffclCorpActnEvtId: in 37 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/CorpActnGnlInf/EvtPrcgTp: in 33 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/CorpActnGnlInf/EvtPrcgTp/Cd: in 33 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/AcctDtls/AcctsListAndBalDtls/SfkpgAcct: in 35 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/AcctDtls/AcctsListAndBalDtls/Bal: in 32 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/AcctDtls/AcctsListAndBalDtls/Bal/TtlElgblBal: in 18 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/AcctDtls/AcctsListAndBalDtls/Bal/TtlElgblBal/Bal: in 16 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/AcctDtls/AcctsListAndBalDtls/Bal/TtlElgblBal/Bal/QtyChc: in 16 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/AcctDtls/AcctsListAndBalDtls/Bal/TtlElgblBal/Bal/QtyChc/SgndQty: in 16 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/AcctDtls/AcctsListAndBalDtls/Bal/TtlElgblBal/Bal/QtyChc/SgndQty/ShrtLngPos: in 16 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/AcctDtls/AcctsListAndBalDtls/Bal/TtlElgblBal/Bal/QtyChc/SgndQty/Qty: in 16 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/AcctDtls/AcctsListAndBalDtls/Bal/TtlElgblBal/Bal/QtyChc/SgndQty/Qty/FaceAmt: in 7 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/AcctDtls/AcctsListAndBalDtls/Bal/TtlElgblBal/Bal/QtyChc/SgndQty/Qty/Unit: in 9 of 50 files -> optional. Please confirm.
- Document/CorpActnMvmntPrlimryAdvc/AcctDtls/AcctsListAndBalDtls/Bal/CollInBal: in 22 of 50 files -> optional. Please confirm.
```

**What infer cannot detect** (from reading `XsdWriter`/`ReviewReportWriter`/`DatatypeGuesser`, and
confirmed empirically by the 1/50 result above):
- `xs:choice` — never modeled; everything is emitted as `xs:sequence`. This is the direct cause of the 49/50 validation failures above.
- `xs:pattern` facets — not inferred at all; leaves fall back to coarse datatype guessing only.
- Length facets (`minLength`/`maxLength`/`length`), `totalDigits`/`fractionDigits`, `min/maxInclusive/Exclusive` — none inferred.
- Enumerations — only **flagged** in the review report as a note ("only X, Y seen → could be an enumeration. Please confirm.") when a leaf has 2–6 distinct observed values across all samples (`ReviewReportWriter.ENUM_MAX_DISTINCT = 6`); never written into the XSD as an actual `xs:enumeration` restriction.
- `xs:all` — never emitted; every complex type uses `xs:sequence`.
- Type reuse/deduplication — every element position gets its own freshly-named complexType (`newTypeName`, `XsdWriter.java:71-75`), even if structurally identical to one used elsewhere; confirmed above (183 inferred complexTypes vs. 95 in the original for the same 65-vs-143-name vocabulary).
- Attribute/element namespaces beyond the single root-level `targetNamespace` — `XmlStructureScanner` does track each element's/attribute's `QName`, but `XsdWriter` only ever uses the root's namespace for `targetNamespace`; nothing attribute-specific (e.g. `attributeFormDefault`) is inferred.
- `nillable`, annotations/documentation — not read from source XMLs (there is nothing to read — plain XML instances don't carry nillable/documentation — and nothing is emitted for them either).

---

## 5. Tests

| Test class | What it tests |
|---|---|
| `GenerateCommandTest.defaultCountIsOneThousand` | `GenerateOptions.parse` with no `--count` flag defaults `count` to 1000 |
| `GenerateCommandTest.generatedXmlsAreValid` | Runs a real `GenerateCommand.run` (count=6, mixed, seed=42, against the real `supl.009` XSD in `../xsd/`) and asserts `invalidCount==0` and that every written file independently validates via `XsdValidatorCache` |
| `GenerateCommandTest.sameSeedGivesSameOutput` | Runs two separate `GenerateCommand.run` calls with the same seed (123, count=3) into different directories and asserts the files are byte-identical pairwise |
| `InferCommandTest.detectsOptionalAndRepeatingAndDatatypes` | Hand-written 2-file `Order`/`Item` sample set; asserts the inferred XSD marks `Id` as required `xs:integer`, `Note` as optional `xs:string`, `Item` as `maxOccurs="unbounded"`, and that the review report mentions `Note` |
| `InferCommandTest.warnsWhenOnlyOneSampleGiven` | Single-sample input; asserts the review report text contains a "warning" |

**`mvn test` result** (on this machine, module `xml-toolkit`):

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 13.93 s -- in com.project.toolkit.GenerateCommandTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.131 s -- in com.project.toolkit.InferCommandTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

All 5 tests pass. (No test exercises `xs:any`, `xs:choice`-branch coverage, or the infer-mode
`xs:choice` gap described in section 4.)

---

## 6. Known limitations

**Explicit markers in code:** none. `grep -rniE "TODO|FIXME|simplifi|hack|workaround|XXX"` across
all of `xml-toolkit/src` returns zero matches.

**Weak points found by reading the code and/or confirmed by real run output in this report**
(cross-referenced to the section that establishes each fact):

1. `xs:any` is filled with one fixed, hardcoded stub element regardless of the actual extension schema or namespace rules declared by `processContents`/`namespace` (section 2).
2. `nillable`, `default`, and `fixed` facets are never read anywhere in the generate code (section 2) — not exercised by the 4 checked-in XSDs, but a real gap if used against a different schema.
3. The per-file retry mechanism (up to 5 attempts) does not expose, in any printed output, how many files needed a retry versus passed on the first attempt — only final valid/invalid totals and a `[WARN]` for files that exhaust all 5 attempts (section 3).
4. `XsdPatternSampler` does not support regex anchors, backreferences, lookahead/lookbehind, Unicode property escapes, non-greedy quantifiers, or negated shorthand classes `\D`/`\W`/`\S` (silently treated as the literal escaped character) (section 2).
5. Infer mode cannot detect `xs:choice`, which causes a severe, measured drop in round-trip validity: only 1 of 50 real `seev.035` samples validated against the XSD inferred from those same 50 samples (section 4).
6. Infer mode never deduplicates/reuses complexTypes across different parent contexts, so the inferred XSD for `seev.035` has more total element declarations (340 vs. 227) and more complexTypes (183 vs. 95) than the original despite describing fewer distinct element names (143 vs. 65) (section 4).
7. Generate throughput varies substantially by schema: 0.4–0.5 files/sec for `seev.035`/`seev.036` versus 1.9–2.6 files/sec for `supl.009`/`supl.011` at `--size mixed --count 200` on this machine (section 3) — observed only; no profiling was done to attribute this to a specific cause.
