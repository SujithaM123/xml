import { useEffect, useMemo, useState } from 'react'
import SchemaSelector from '../components/SchemaSelector'
import SearchableSelect, { type SelectOption } from '../components/ui/SearchableSelect'
import MultiSelectTags from '../components/ui/MultiSelectTags'
import { api } from '../api/client'
import { useJob } from '../lib/useJob'
import type { GenerateReport, SchemaRecord, SchemaScanResult } from '../api/types'

/** Every error type the engine actually supports (SchemaScanner/XmlInstanceGenerator) -- the
 * label text is tightened up for the UI, but the value is exactly what the API expects. No type
 * is invented here that the engine doesn't implement. */
const ERROR_TYPE_LABELS: Record<string, { label: string; description: string }> = {
  'missing-required': { label: 'Missing required elements', description: 'Drops a required element so the file fails validation.' },
  'missing-attribute': { label: 'Missing attributes', description: 'Drops a required attribute from an element that has one.' },
  'invalid-code': { label: 'Invalid enumeration values', description: 'Writes a code outside the allowed enumeration.' },
  'pattern-break': { label: 'Pattern violations', description: 'Writes a value that breaks an xs:pattern facet.' },
  'too-long': { label: 'Too long values', description: 'Exceeds a maxLength facet.' },
  'wrong-datatype': { label: 'Invalid datatypes', description: 'Writes a value of the wrong primitive datatype.' },
  'invalid-date': { label: 'Invalid dates', description: 'Writes an out-of-range date (e.g. month 13) for a date-typed field.' },
  'invalid-attribute-value': { label: 'Invalid attribute values', description: "Corrupts an attribute's value for its datatype or facets." },
  'too-many-repeats': { label: 'Occurrence violations', description: 'Repeats an element more times than maxOccurs allows.' },
  'invalid-namespace': { label: 'Invalid namespaces', description: 'Writes an element under a namespace the schema does not expect.' },
  'unexpected-element': { label: 'Unexpected elements', description: 'Inserts an element the schema never declared.' },
  'invalid-characters': { label: 'Invalid characters', description: 'Injects an XML 1.0-illegal character into a text value.' },
  'malformed-xml': { label: 'Malformed XML', description: 'Removes the closing tag, producing a non-well-formed file.' },
}

const COMPLEXITY_OPTIONS: SelectOption[] = [
  { value: '1', label: 'Primary', description: 'Required elements only.' },
  { value: '2', label: 'Advanced', description: 'Required and optional elements.' },
  { value: '3', label: 'Comprehensive', description: 'Required, optional and repeated elements.' },
]

const SIZE_OPTIONS: SelectOption[] = [
  { value: 'small', label: 'Small' },
  { value: 'medium', label: 'Medium' },
  { value: 'large', label: 'Large' },
  { value: 'mixed', label: 'Mixed' },
]

interface SavedProfile {
  name: string
  count: number
  level: number
  size: string
  errorPercent: number
  errorTypes: string[]
  seed: string
  threads: string
}

export default function GeneratePage() {
  const [schemaId, setSchemaId] = useState('')
  const [schema, setSchema] = useState<SchemaRecord | null>(null)
  const [scan, setScan] = useState<SchemaScanResult | null>(null)
  const [scanError, setScanError] = useState<string | null>(null)

  const [includeSupplement, setIncludeSupplement] = useState(false)
  const [count, setCount] = useState(1000)
  const [level, setLevel] = useState(3)
  const [size, setSize] = useState('mixed')
  const [errorPercent, setErrorPercent] = useState(10)
  const [selectedErrorTypes, setSelectedErrorTypes] = useState<string[]>([])
  const [seed, setSeed] = useState('')
  const [threads, setThreads] = useState('')
  const [output, setOutput] = useState<'file' | 'stream' | 'both'>('file')

  const [jobId, setJobId] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [report, setReport] = useState<GenerateReport | null>(null)

  const [savedProfiles, setSavedProfiles] = useState<SavedProfile[]>([])
  const [profileName, setProfileName] = useState('')

  const job = useJob(jobId)

  useEffect(() => {
    const raw = localStorage.getItem('xml-toolkit:generate-profiles')
    if (raw) {
      try {
        setSavedProfiles(JSON.parse(raw))
      } catch {
        // ignore a corrupted/old-format value rather than block the page
      }
    }
  }, [])

  useEffect(() => {
    if (!schemaId) {
      setScan(null)
      return
    }
    setScanError(null)
    api
      .scanSchema(schemaId)
      .then(setScan)
      .catch((e) => setScanError(String(e)))
  }, [schemaId])

  useEffect(() => {
    if (job.status === 'SUCCEEDED' && jobId) {
      api.jobReportJson(jobId).then(setReport).catch(() => {})
    }
  }, [job.status, jobId])

  useEffect(() => {
    if (!scan) return
    setSelectedErrorTypes((prev) => prev.filter((t) => scan.possibleErrorTypes.includes(t)))
  }, [scan])

  const canIncludeSupplement = scan ? scan.anySlotCount > 0 && schema?.linkedSupplementId != null : false
  const availableLevels = scan?.possibleLevels ?? [1, 2, 3]
  // Every type the engine implements, shown up front so there's something to pick even before a
  // schema is chosen. Once a schema is scanned, a type that schema genuinely can't produce (e.g.
  // no attributes, no date-typed fields) is disabled rather than hidden, so the list never looks
  // empty and it's clear *why* something isn't pickable.
  const supportedErrorTypes = scan?.possibleErrorTypes ?? null

  const complexityOptions = useMemo(
    () =>
      COMPLEXITY_OPTIONS.map((opt) => ({
        ...opt,
        disabled: !availableLevels.includes(Number(opt.value)),
        disabledReason: 'Not applicable to this schema.',
      })),
    [availableLevels],
  )

  const errorMultiOptions = useMemo(
    () =>
      Object.entries(ERROR_TYPE_LABELS).map(([value, meta]) => ({
        value,
        label: meta.label,
        description: meta.description,
        disabled: supportedErrorTypes != null && !supportedErrorTypes.includes(value),
        disabledReason: 'This schema has nothing this error type could apply to.',
      })),
    [supportedErrorTypes],
  )

  const scanSummary = useMemo(() => {
    if (!scan) return ''
    return `required=${scan.requiredCount} optional=${scan.optionalCount} choices=${scan.choiceCount} enums=${scan.enumerationCount} patterns=${scan.patternCount} xs:any=${scan.anySlotCount} maxDepth=${scan.maxDepth}`
  }, [scan])

  async function handleGenerate() {
    if (!schemaId) {
      setSubmitError('Select or upload an XSD first.')
      return
    }
    setSubmitting(true)
    setSubmitError(null)
    setReport(null)
    try {
      const { jobId: newJobId } = await api.generate({
        schemaId,
        count,
        level,
        size: level === 3 ? size : undefined,
        errorPercent: selectedErrorTypes.length > 0 ? errorPercent : 0,
        errorTypes: selectedErrorTypes.length > 0 ? selectedErrorTypes : undefined,
        seed: seed ? Number(seed) : undefined,
        threads: threads ? Number(threads) : undefined,
        includeSupplement: canIncludeSupplement && includeSupplement,
      })
      setJobId(newJobId)
    } catch (e) {
      setSubmitError(String(e))
    } finally {
      setSubmitting(false)
    }
  }

  async function handleCancel() {
    if (jobId) await api.cancelJob(jobId)
  }

  function saveProfile() {
    if (!profileName.trim()) return
    const profile: SavedProfile = { name: profileName.trim(), count, level, size, errorPercent, errorTypes: selectedErrorTypes, seed, threads }
    const next = [...savedProfiles.filter((p) => p.name !== profile.name), profile]
    setSavedProfiles(next)
    localStorage.setItem('xml-toolkit:generate-profiles', JSON.stringify(next))
    setProfileName('')
  }

  function loadProfile(name: string) {
    const profile = savedProfiles.find((p) => p.name === name)
    if (!profile) return
    setCount(profile.count)
    setLevel(profile.level)
    setSize(profile.size)
    setErrorPercent(profile.errorPercent)
    setSelectedErrorTypes(profile.errorTypes)
    setSeed(profile.seed)
    setThreads(profile.threads)
  }

  const isRunning = job.status === 'PENDING' || job.status === 'RUNNING'

  return (
    <div>
      <h1>Generate XMLs</h1>
      <div className="two-column">
        <div className="panel">
          <div className="section-title">Schema</div>
          <SchemaSelector
            value={schemaId}
            onChange={(id, s) => {
              setSchemaId(id)
              setSchema(s)
              setIncludeSupplement(false)
            }}
          />
          {scanError && <div className="error-text">{scanError}</div>}
          {scan && (
            <div className="muted" style={{ marginBottom: '1rem' }}>
              {scanSummary}
            </div>
          )}

          {canIncludeSupplement && (
            <div className="field">
              <label>
                <input type="checkbox" checked={includeSupplement} onChange={(e) => setIncludeSupplement(e.target.checked)} />
                Include DTCC supplement document inside the xs:any slot
              </label>
            </div>
          )}

          <div className="field">
            <label htmlFor="count">Number of XMLs</label>
            <input id="count" type="number" min={1} value={count} onChange={(e) => setCount(Number(e.target.value))} />
          </div>

          <div className="field">
            <label htmlFor="complexity">Content Complexity</label>
            <SearchableSelect
              id="complexity"
              options={complexityOptions}
              value={String(level)}
              onChange={(v) => setLevel(Number(v))}
              searchable={false}
            />
          </div>

          {level === 3 && (
            <div className="field">
              <label htmlFor="xml-size">XML Size</label>
              <SearchableSelect id="xml-size" options={SIZE_OPTIONS} value={size} onChange={setSize} searchable={false} />
            </div>
          )}

          <div className="field">
            <label htmlFor="error-config">
              Error Configuration
              <span className="tip" title="Choose which validation-breaking error types can be injected into a percentage of the generated files.">
                i
              </span>
            </label>
            <MultiSelectTags
              id="error-config"
              options={errorMultiOptions}
              selected={selectedErrorTypes}
              onChange={setSelectedErrorTypes}
              placeholder="No errors (clean output)"
            />
            {!schemaId && (
              <div className="hint" style={{ marginTop: '0.3rem' }}>
                Select an XSD to see which of these it actually supports -- unsupported ones disable automatically.
              </div>
            )}
            {selectedErrorTypes.length > 0 && (
              <div style={{ marginTop: '0.6rem' }}>
                <label htmlFor="error-percent" className="muted">
                  Percent of files with an injected error
                </label>
                <input
                  id="error-percent"
                  type="number"
                  min={1}
                  max={100}
                  value={errorPercent}
                  onChange={(e) => setErrorPercent(Number(e.target.value))}
                />
              </div>
            )}
          </div>

          <details className="advanced">
            <summary>Advanced settings</summary>
            <div className="field" style={{ marginTop: '0.75rem' }}>
              <label htmlFor="seed">Seed (blank = random)</label>
              <input id="seed" type="text" value={seed} onChange={(e) => setSeed(e.target.value)} placeholder="e.g. 42" />
            </div>
            <div className="field">
              <label htmlFor="threads">Threads (blank = all CPU cores)</label>
              <input id="threads" type="text" value={threads} onChange={(e) => setThreads(e.target.value)} />
            </div>
          </details>

          <div className="field" style={{ marginTop: '1rem' }}>
            <label>Output</label>
            <div className="radio-row">
              <label>
                <input type="radio" name="output" checked={output === 'file'} onChange={() => setOutput('file')} />
                File (ZIP download)
              </label>
              <label>
                <input type="radio" name="output" checked={output === 'stream'} onChange={() => setOutput('stream')} />
                Stream to pipeline
              </label>
              <label>
                <input type="radio" name="output" checked={output === 'both'} onChange={() => setOutput('both')} />
                Both
              </label>
            </div>
          </div>

          <div className="btn-row">
            <button className="primary" onClick={handleGenerate} disabled={submitting || isRunning}>
              {submitting ? 'Starting...' : 'Generate'}
            </button>
            {isRunning && <button onClick={handleCancel}>Cancel</button>}
          </div>
          {submitError && <div className="error-text">{submitError}</div>}

          {jobId && (
            <div style={{ marginTop: '1rem' }}>
              <div className="progress-bar">
                <div className="progress-bar-fill" style={{ width: `${job.progressPercent}%` }} />
              </div>
              <div className="muted">
                {job.status} — {job.progressPercent}%
              </div>
              {job.status === 'FAILED' && <div className="error-text">{job.errorMessage}</div>}
            </div>
          )}

          {report && (
            <div className="result-card">
              <h2>Result</h2>
              <div className="stat-grid">
                <div className="stat">
                  <div className="value">{report.counts.totalRequested}</div>
                  <div className="label">Total</div>
                </div>
                <div className="stat">
                  <div className="value">{report.counts.valid}</div>
                  <div className="label">Valid</div>
                </div>
                <div className="stat">
                  <div className="value">{Object.values(report.counts.errorByType).reduce((a, b) => a + b, 0)}</div>
                  <div className="label">Errors</div>
                </div>
                <div className="stat">
                  <div className="value">{report.timeSeconds.toFixed(2)}s</div>
                  <div className="label">Time</div>
                </div>
                <div className="stat">
                  <div className="value">{report.filesPerSecond.toFixed(1)}</div>
                  <div className="label">Files/sec</div>
                </div>
              </div>
              {Object.keys(report.counts.errorByType).length > 0 && (
                <div className="muted">
                  Errors by type: {Object.entries(report.counts.errorByType).map(([k, v]) => `${k}=${v}`).join(', ')}
                </div>
              )}
              <div className="muted">
                Size per file: min {(report.sizeBytes.min / 1024).toFixed(1)}KB, avg {(report.sizeBytes.avg / 1024).toFixed(1)}KB, max{' '}
                {(report.sizeBytes.max / 1024).toFixed(1)}KB
              </div>
              <div className="btn-row">
                <a href={api.jobDownloadUrl(jobId!)}>
                  <button>Download ZIP</button>
                </a>
                <button onClick={() => api.jobReport(jobId!, 'csv').then((csv) => downloadText(csv, 'report.csv'))}>
                  Download report (CSV)
                </button>
                <button onClick={() => api.jobReport(jobId!, 'json').then((j) => downloadText(j, 'report.json'))}>
                  Download report (JSON)
                </button>
              </div>
              <div className="field" style={{ marginTop: '1rem' }}>
                <label htmlFor="profile-name">Save settings as profile</label>
                <div className="btn-row" style={{ marginTop: 0 }}>
                  <input id="profile-name" type="text" value={profileName} onChange={(e) => setProfileName(e.target.value)} placeholder="profile name" />
                  <button onClick={saveProfile}>Save</button>
                </div>
              </div>
            </div>
          )}

          {savedProfiles.length > 0 && (
            <div className="field" style={{ marginTop: '1rem' }}>
              <label>Load a saved profile</label>
              <select defaultValue="" onChange={(e) => e.target.value && loadProfile(e.target.value)}>
                <option value="">-- choose a saved profile --</option>
                {savedProfiles.map((p) => (
                  <option key={p.name} value={p.name}>
                    {p.name}
                  </option>
                ))}
              </select>
            </div>
          )}
        </div>

        <div className="panel">
          <h2>Live preview</h2>
          <SamplePreview schemaId={schemaId} level={level} size={level === 3 ? size : undefined} />
        </div>
      </div>
    </div>
  )
}

function downloadText(content: string, fileName: string) {
  const blob = new Blob([content], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.click()
  URL.revokeObjectURL(url)
}

/** A tiny, disposable preview job (count=1) so the right-hand panel always shows one real,
 * freshly-generated sample for the current settings -- not a canned example. */
function SamplePreview({ schemaId, level, size }: { schemaId: string; level: number; size?: string }) {
  const [xml, setXml] = useState<string>('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!schemaId) {
      setXml('')
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    api
      .generate({ schemaId, count: 1, level, size })
      .then(async ({ jobId }) => {
        for (let i = 0; i < 100 && !cancelled; i++) {
          const status = await api.jobStatus(jobId)
          if (status.status === 'SUCCEEDED') {
            const res = await fetch(api.jobDownloadUrl(jobId))
            const blob = await res.blob()
            const JSZipLike = await import('../lib/readFirstZipEntry')
            const text = await JSZipLike.readFirstZipEntry(blob)
            if (!cancelled) setXml(text)
            return
          }
          if (status.status === 'FAILED' || status.status === 'CANCELLED') {
            if (!cancelled) setError(status.errorMessage || status.status)
            return
          }
          await new Promise((r) => setTimeout(r, 300))
        }
      })
      .catch((e) => !cancelled && setError(String(e)))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [schemaId, level, size])

  if (!schemaId) {
    return (
      <div className="empty-state">
        <svg width="32" height="32" viewBox="0 0 24 24" fill="none">
          <path
            d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8l-6-6z"
            stroke="currentColor"
            strokeWidth="1.4"
            strokeLinejoin="round"
          />
          <path d="M14 2v6h6" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
        </svg>
        <strong>No schema selected</strong>
        <span>Pick or upload an XSD on the left to see a live, freshly-generated sample here.</span>
      </div>
    )
  }
  if (loading) return <div className="muted">Generating preview...</div>
  if (error) return <div className="error-text">{error}</div>
  return <pre className="code-preview">{xml || 'No preview yet.'}</pre>
}
