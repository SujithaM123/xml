# xml-toolkit

A Java toolkit with two core commands, wrapped in a REST API and a web UI:

- **generate**: reads an XSD and writes realistic, XSD-valid sample XMLs -- content levels,
  size tiers, deliberate error injection, DTCC supplement embedding, parallel generation, and a
  full JSON/CSV report.
- **infer**: reads a folder of sample XMLs and writes a best-guess XSD plus a plain-language,
  confidence-ranked review report.

Three Maven modules under the `message-platform` parent, plus a frontend:

```
message-platform/
  pom.xml                   parent (packaging=pom)
  xsd/                      4 real ISO 20022 sample XSDs
  xml-toolkit/              the engine (CLI) -- generate / infer / scan
  xml-toolkit-api/          Spring Boot 3 REST API wrapping the engine
  xml-toolkit-ui/           React + TypeScript + Vite frontend for the API
```

---

## Setting up on a new machine (first time only)

Follow these steps in order. Each one assumes the previous one finished successfully.

### 1. Install prerequisites

| Tool | Minimum version | Check it's installed and on PATH |
|---|---|---|
| Java (a JDK, not just a JRE) | 17 or newer | `java -version` |
| Maven | 3.6 or newer | `mvn -version` |
| Node.js | 18 or newer | `node -v` |
| npm | comes bundled with Node | `npm -v` |

Run each check command in a terminal. If any of them says "command not found" (or similar),
install that tool before continuing -- everything below depends on all four being available.

### 2. Get the project onto the machine

Copy the whole project folder over -- the one containing this `README.md`, `pom.xml`, `xsd/`,
`xml-toolkit/`, `xml-toolkit-api/`, `xml-toolkit-ui/`. You do **not** need to bring:

- `target/` folders inside `xml-toolkit/` or `xml-toolkit-api/` -- rebuilt by step 3
- `node_modules/` or `dist/` inside `xml-toolkit-ui/` -- rebuilt by step 5
- `xml-toolkit-api-data/` -- the backend recreates this itself the first time it runs (see
  [Where everything gets stored](#where-everything-gets-stored) below)
- `generated/` or `inferred/` if present -- these are just leftover output from manually running
  the CLI, not part of the project itself

Leaving those out just means a smaller copy; bringing them along by accident won't break
anything either.

### 3. Build the Java side (engine + API)

Open a terminal **at the project root** (the folder with `pom.xml` in it) and run:

```bash
mvn clean package
```

This compiles both `xml-toolkit` (the engine) and `xml-toolkit-api` (the REST API) and runs
their test suites. It should end with `BUILD SUCCESS`. Afterward you'll have:

- `xml-toolkit/target/xml-toolkit.jar`
- `xml-toolkit-api/target/xml-toolkit-api.jar`

You only need to repeat this step after changing Java code (see
[Rebuilding](#rebuilding-after-changing-java-code) below for the one thing to watch out for).

### 4. Start the backend

Still from the **project root** (this matters -- see the note below):

```bash
java -jar xml-toolkit-api/target/xml-toolkit-api.jar
```

Leave this terminal open; it's your running server. It listens on `http://localhost:8080`.

> **Always start it from the project root.** The backend stores its data in a folder named
> `xml-toolkit-api-data`, created automatically relative to wherever you launch the `java -jar`
> command from. Launch it from a different folder and it'll create a second, separate (empty)
> data folder there instead -- confusingly, with your schemas/history missing. One terminal
> starting point, every time, avoids that.

### 5. Build and start the frontend

Open a **second terminal** (leave the backend's terminal running):

```bash
cd xml-toolkit-ui
npm install
npm run dev
```

`npm install` downloads the frontend's dependencies and only needs to run once (or again after
`package.json` changes). `npm run dev` starts the UI on `http://localhost:5173` and
automatically forwards its `/api/*` calls to the backend on port 8080 -- no extra configuration
needed, as long as the backend from step 4 is already running.

### 6. Open the app

**http://localhost:5173/**

You'll start with an empty Registry and History (this is a fresh install) -- use the **Generate
XMLs** or **Create XSD** page to upload/register an XSD and get going. Four sample ISO 20022
XSDs are already in the `xsd/` folder if you want something to try immediately.

---

## Everyday use (after the first setup)

Every time you come back to work on this:

```bash
# Terminal 1 -- from the project root
java -jar xml-toolkit-api/target/xml-toolkit-api.jar

# Terminal 2
cd xml-toolkit-ui
npm run dev
```

(No `npm install` needed again unless `package.json` changed, and no `mvn package` needed again
unless the Java code changed.) Open `http://localhost:5173/`. Stop either one with `Ctrl+C` in
its own terminal when you're done.

### Rebuilding after changing Java code

**Stop the backend first** (`Ctrl+C` in its terminal), *then* run `mvn clean package`. Maven's
`clean` step deletes the old `xml-toolkit-api.jar` before rebuilding it, and Windows (and most
OSes) won't let it delete a file that a running `java` process still has open. If you forget and
see an error like:

```
Failed to execute goal ... clean ... Failed to delete ...xml-toolkit-api.jar
```

it means the backend from a previous session is still running somewhere. Stop it, then re-run
`mvn clean package`.

### "Web server failed to start. Port 8080 was already in use."

This means a backend is already running (maybe you forgot to stop a previous one, or started it
twice). Find and stop it:

**Windows (PowerShell):**
```powershell
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*xml-toolkit-api*' } | Select-Object ProcessId
Stop-Process -Id <id> -Force
```

**macOS / Linux:**
```bash
lsof -i :8080
kill <pid>
```

Then start the backend again.

### "500 Internal Server Error" on the Registry or History page

This almost always means the frontend (port 5173) is running but the backend (port 8080) isn't
-- the dev server's proxy can't reach it and reports it as a 500 instead of a clearer "can't
connect" message. Make sure the backend from step 4 above is actually running and listening on
port 8080, then refresh the page.

---

## Where everything gets stored

| Folder | What's in it | Safe to delete? |
|---|---|---|
| `xml-toolkit-api-data/` | The running app's real data: `registry.json` (registered schemas), `schemas/` (their uploaded `.xsd` bytes), `history.json` (past runs), `jobs/<job-id>/` (each run's generated zip / inferred XSD / reports) -- everything you see in the Registry and History pages lives here | Yes, but you lose all registered schemas and history. The backend recreates an empty one automatically next time it starts -- there's no way to run it without this folder reappearing, it just starts empty. |
| `xml-toolkit/target/`, `xml-toolkit-api/target/` | Maven build output (compiled classes, the runnable jars) | Yes -- `mvn clean package` regenerates it |
| `xml-toolkit-ui/node_modules/`, `xml-toolkit-ui/dist/` | npm dependencies / production build output | Yes -- `npm install` / `npm run build` regenerate them |
| `generated/`, `inferred/` (if present) | Output from manually running the CLI (`java -jar xml-toolkit.jar generate/infer ...`) directly, outside the app | Yes -- not referenced by any code; purely wherever you pointed `--out` |

Normal use through the UI never touches `generated/` or `inferred/` at all -- everything goes
through `xml-toolkit-api-data/jobs/` and comes back to you via the Download buttons in the
browser.

---

## Build everything (reference)

```bash
mvn clean package
```

Builds all three Maven modules and runs the Java test suite (engine + API). Run just the tests
with `mvn clean test`.

To build only one module (plus whatever it depends on), use `-pl <module> -am`, e.g.
`mvn -pl xml-toolkit-api -am package`.

---

## The engine: `xml-toolkit/target/xml-toolkit.jar`

### generate (XSD -> XMLs)

```bash
java -jar xml-toolkit/target/xml-toolkit.jar generate \
  --xsd xsd/SomeMessage.xsd --out generated/SomeMessage \
  --count 1000 --size mixed --seed 42
```

| Flag | Meaning | Default |
|---|---|---|
| `--xsd` | input XSD | required |
| `--out` | output folder, or the zip file path when `--output zip` | required unless `--output stream` |
| `--count` | how many XML files | `1000` |
| `--size` | `small`\|`medium`\|`large`\|`mixed` -- only matters at `--level 3` | `mixed` |
| `--seed` | same seed -> byte-identical output | random |
| `--level` | `1` required-only, `2` required+optional, `3` +repetitions | `3` |
| `--profile` | YAML file overriding name-based value rules (e.g. `PlcAndNm` -> an element path, not a person name) | `xml-toolkit/profiles/default.yaml` behavior built in |
| `--threads` | generation thread pool size | all CPU cores |
| `--output` | `folder`\|`zip`\|`stream` (stream writes a zip byte stream to stdout) | `folder` |
| `--error-percent` / `--error-types` | inject exactly one deliberate, validation-failing error into that percent of files, from the given types (comma list; default: every type the schema supports) | `0` (off) |
| `--supplement` | another XSD to generate a valid document for and embed inside every `xs:any` slot (e.g. seev.035 -> supl.009), independently validated against its own schema first | none |

Every run writes `report.json`/`report.csv` (settings, seed, counts, size stats, time,
files/sec) and `manifest.json`/`manifest.csv` (one row per file: valid, or which error was
injected and where) alongside the output.

Use `java -jar xml-toolkit.jar scan --xsd <file>` to see what a schema actually contains
(required/optional/choice/enum/pattern counts, which error types it supports) before generating.

### infer (XMLs -> XSD)

```bash
java -jar xml-toolkit/target/xml-toolkit.jar infer \
  --xml generated/SomeMessage --out inferred/SomeMessage.xsd [--baseline previous.xsd]
```

Writes the inferred XSD plus `<name>_review.md` and `<name>_review.json` next to it -- only the
genuinely doubtful calls (low sample count, a near-unanimous one-occurrence split, a real
enumeration/choice candidate), each tagged with a confidence level; everything high-confidence is
auto-accepted and just counted, not listed. `--baseline` switches the report to only the
differences from a previously-written XSD.

---

## The API: `xml-toolkit-api`

```bash
java -jar xml-toolkit-api/target/xml-toolkit-api.jar
```

Starts on `http://localhost:8080` with sensible defaults (data stored in `./xml-toolkit-api-data`
relative to wherever you launch it from). To override them:

```bash
java -jar xml-toolkit-api/target/xml-toolkit-api.jar \
  --app.allowed-base-folders=/path/to/a/safe/folder \
  --app.data-dir=./xml-toolkit-api-data
```

`app.allowed-base-folders` is the whitelist a server-side path (schema upload target, infer
`serverPath` input) must resolve inside -- only needed if you use the "server path" input mode.
`app.data-dir` is where the registry, job output, decisions, and history are persisted (created
automatically if missing).

Endpoints: `/api/schemas` (list/register/scan/download), `/api/generate` + `/api/jobs/{id}`
(status, SSE events, cancel, download, report), `/api/infer` (zip/files/serverPath upload) +
`/api/infer/{id}/{groups,review,decisions,register,xsd}`, `/api/history` (+ rerun). See the
controller classes under `xml-toolkit-api/src/main/java/.../api/controller` for exact request/
response shapes.

Known limitation: `POST /api/infer/{jobId}/decisions` records a human's decision and won't
re-ask about it on a later run, but doesn't yet rewrite the XSD from most decision kinds (the
engine doesn't support that yet) -- the response says so explicitly.

## The UI: `xml-toolkit-ui`

```bash
cd xml-toolkit-ui
npm install
npm run dev        # http://localhost:5173, proxies /api to http://localhost:8080
```

Needs the API running (above) at `localhost:8080` -- the dev server proxies `/api/*` to it (see
`vite.config.ts`). `npm run build` produces a static `dist/` you can serve from anything; `npm
test` runs the Vitest unit/component suite.

Four pages (top nav): **Generate XMLs** (pick/upload a schema, settings, live sample preview,
SSE progress, result card with download/report/save-as-profile), **Create XSD** (folder picker
with in-browser grouping+sampling, ZIP upload, or server path; group-by-group review with
accept/reject decisions and rebuild; download/register), **Registry** (every registered schema,
its scan summary, download), **History** (past runs, re-run).

---

## A note on the real XSDs used here

The 4 XSDs checked into `xsd/` are real ISO 20022 "corporate action" message schemas (SWIFT/DTCC
2025 standard release): `seev.035` / `seev.036` (preliminary advice / confirmation) and their
`supl.009` / `supl.011` DTCC supplementary extensions. They're large (71-227 declared elements
each), self-contained (no `xs:import`/`xs:include` between them), and use `Document` as the single
root element in each case.

See `xml-toolkit/REVIEW_FACTS.md` for the original factual engine review, including the before/
after numbers for the infer-mode fixes (round-trip validation on 50 real seev.035 samples went
from 1/50 to 50/50, and inferred complexType count from 183 down to 89, against the original
schema's own 95).
