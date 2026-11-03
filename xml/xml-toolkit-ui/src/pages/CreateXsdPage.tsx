import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { useJob } from '../lib/useJob'
import { groupFilesInBrowser, smartSample, type BrowserFileGroup } from '../lib/browserXmlGrouper'
import FileDropzone from '../components/ui/FileDropzone'
import SearchableSelect, { type SelectOption } from '../components/ui/SearchableSelect'
import type { FileGroup, ReviewItem, SchemaRecord } from '../api/types'

type InputMode = 'folder' | 'zip' | 'serverPath'

const MAX_SAMPLES_DEFAULT = 200

const SOURCE_CARDS: { mode: InputMode; title: string; desc: string; icon: JSX.Element }[] = [
  {
    mode: 'folder',
    title: 'Local Folder',
    desc: 'Pick XML files or a whole folder from this computer.',
    icon: (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
        <path d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V7z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
      </svg>
    ),
  },
  {
    mode: 'zip',
    title: 'ZIP Upload',
    desc: 'Upload a single ZIP archive containing your sample XMLs.',
    icon: (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
        <path d="M4 4h16v16H4V4z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
        <path d="M10 4v2M14 4v2M10 8v2M14 8v2M10 12v2M14 12v2" stroke="currentColor" strokeWidth="1.6" />
      </svg>
    ),
  },
  {
    mode: 'serverPath',
    title: 'Server Directory',
    desc: 'Point at a folder already on the server, inside an allowed base path.',
    icon: (
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
        <rect x="3" y="4" width="18" height="6" rx="1" stroke="currentColor" strokeWidth="1.6" />
        <rect x="3" y="14" width="18" height="6" rx="1" stroke="currentColor" strokeWidth="1.6" />
        <path d="M7 7h.01M7 17h.01" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      </svg>
    ),
  },
]

export default function CreateXsdPage() {
  const [mode, setMode] = useState<InputMode>('folder')
  const [xmlFiles, setXmlFiles] = useState<File[]>([])
  const [browserGroups, setBrowserGroups] = useState<BrowserFileGroup[]>([])
  const [browserBroken, setBrowserBroken] = useState<File[]>([])
  const [selectedGroups, setSelectedGroups] = useState<Set<string>>(new Set())
  const [scanning, setScanning] = useState(false)

  const [zipFiles, setZipFiles] = useState<File[]>([])
  const [serverPath, setServerPath] = useState('')

  const [schemas, setSchemas] = useState<SchemaRecord[]>([])
  const [baselineSchemaId, setBaselineSchemaId] = useState('')
  const [baselineUploading, setBaselineUploading] = useState(false)
  const [baselineError, setBaselineError] = useState<string | null>(null)

  const [jobId, setJobId] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const job = useJob(jobId)

  const [serverGroups, setServerGroups] = useState<FileGroup[]>([])
  const [brokenFiles, setBrokenFiles] = useState<string[]>([])
  const [activeGroup, setActiveGroup] = useState('')
  const [review, setReview] = useState<ReviewItem[]>([])
  const [decisions, setDecisions] = useState<Record<string, string>>({})
  const [rebuildResult, setRebuildResult] = useState<{ validSamples: number; totalSamples: number; note: string } | null>(null)
  const [registered, setRegistered] = useState<Record<string, SchemaRecord>>({})
  const [actionError, setActionError] = useState<string | null>(null)

  useEffect(() => {
    api.listSchemas().then(setSchemas).catch(() => {})
  }, [])

  useEffect(() => {
    if (job.status === 'SUCCEEDED' && jobId) {
      api.inferGroups(jobId).then((g) => {
        setServerGroups(g.groups)
        setBrokenFiles(g.brokenFiles)
        if (g.groups.length > 0) setActiveGroup(g.groups[0].groupKey)
      })
    }
  }, [job.status, jobId])

  useEffect(() => {
    if (jobId && activeGroup && job.status === 'SUCCEEDED') {
      loadReview()
    }
  }, [jobId, activeGroup, job.status])

  async function loadReview() {
    if (!jobId || !activeGroup) return
    const r = await api.inferReview(jobId, activeGroup)
    setReview(r.needsReview)
    setRebuildResult(null)
  }

  async function handleXmlFilesChange(files: File[]) {
    setXmlFiles(files)
    if (files.length === 0) {
      setBrowserGroups([])
      setBrowserBroken([])
      setSelectedGroups(new Set())
      return
    }
    setScanning(true)
    setSubmitError(null)
    try {
      const { groups, brokenFiles: broken } = await groupFilesInBrowser(files)
      setBrowserGroups(groups)
      setBrowserBroken(broken)
      setSelectedGroups(new Set(groups.map((g) => g.groupKey)))
    } catch (e) {
      setSubmitError(String(e))
    } finally {
      setScanning(false)
    }
  }

  function toggleGroup(key: string) {
    setSelectedGroups((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  const toUploadCount = browserGroups
    .filter((g) => selectedGroups.has(g.groupKey))
    .reduce((sum, g) => sum + Math.min(g.files.length, MAX_SAMPLES_DEFAULT), 0)

  async function handleCreateXsd() {
    setSubmitting(true)
    setSubmitError(null)
    setServerGroups([])
    setReview([])
    setRebuildResult(null)
    setRegistered({})
    try {
      const opts = baselineSchemaId ? { baselineSchemaId } : {}
      let result: { jobId: string }
      if (mode === 'folder') {
        const sampledFiles = browserGroups
          .filter((g) => selectedGroups.has(g.groupKey))
          .flatMap((g) => smartSample(g.files, MAX_SAMPLES_DEFAULT))
        if (sampledFiles.length === 0) {
          throw new Error('Choose XML files (or a folder) and select at least one group first.')
        }
        result = await api.inferFromFiles(sampledFiles, opts)
      } else if (mode === 'zip') {
        if (zipFiles.length === 0) throw new Error('Choose a ZIP file first.')
        result = await api.inferFromZip(zipFiles[0], { ...opts, maxSamples: MAX_SAMPLES_DEFAULT })
      } else {
        if (!serverPath.trim()) throw new Error('Enter a server path first.')
        result = await api.inferFromServerPath(serverPath.trim(), { ...opts, maxSamples: MAX_SAMPLES_DEFAULT })
      }
      setJobId(result.jobId)
    } catch (e) {
      setSubmitError(String(e))
    } finally {
      setSubmitting(false)
    }
  }

  async function handleBaselineUpload(file: File) {
    setBaselineUploading(true)
    setBaselineError(null)
    try {
      const record = await api.uploadSchema(file)
      setSchemas((prev) => [...prev, record])
      setBaselineSchemaId(record.id)
    } catch (e) {
      setBaselineError(String(e))
    } finally {
      setBaselineUploading(false)
    }
  }

  function acceptAll() {
    const next = { ...decisions }
    review.forEach((item) => {
      next[item.path] = 'accept'
    })
    setDecisions(next)
  }

  async function handleRebuild() {
    if (!jobId || !activeGroup) return
    setActionError(null)
    try {
      const result = await api.inferDecisions(jobId, activeGroup, decisions)
      setRebuildResult(result)
      await loadReview()
    } catch (e) {
      setActionError(String(e))
    }
  }

  async function handleRegister() {
    if (!jobId || !activeGroup) return
    setActionError(null)
    try {
      const record = await api.inferRegister(jobId, activeGroup)
      setRegistered((prev) => ({ ...prev, [activeGroup]: record }))
    } catch (e) {
      setActionError(String(e))
    }
  }

  function downloadReview() {
    const blob = new Blob([JSON.stringify(review, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'review.json'
    a.click()
    URL.revokeObjectURL(url)
  }

  const isRunning = job.status === 'PENDING' || job.status === 'RUNNING'
  const currentGroupInfo = serverGroups.find((g) => g.groupKey === activeGroup)

  const baselineOptions: SelectOption[] = schemas.map((s) => ({
    value: s.id,
    label: s.name,
    description: s.version ? `version ${s.version}` : undefined,
  }))

  const canSubmit = mode === 'folder' ? xmlFiles.length > 0 : mode === 'zip' ? zipFiles.length > 0 : serverPath.trim().length > 0

  return (
    <div>
      <h1>Create XSD</h1>

      <div className="panel">
        <div className="section-title">XML Source</div>
        <div className="section-desc">Where should the sample XMLs come from?</div>
        <div className="card-select-row">
          {SOURCE_CARDS.map((c) => (
            <button
              key={c.mode}
              type="button"
              className={`card-select${mode === c.mode ? ' card-selected' : ''}`}
              onClick={() => setMode(c.mode)}
            >
              <span className="card-select-icon">{c.icon}</span>
              <span className="card-select-title">{c.title}</span>
              <span className="card-select-desc">{c.desc}</span>
            </button>
          ))}
        </div>

        {mode === 'folder' && (
          <div style={{ marginTop: '1rem' }}>
            <FileDropzone
              files={xmlFiles}
              onFilesChange={handleXmlFilesChange}
              accept={['.xml']}
              multiple
              allowFolderPick
              label="Drop XML files here"
              hint="Only .xml files are accepted. Choosing a folder also works."
            />
            {scanning && <div className="muted" style={{ marginTop: '0.5rem' }}>Scanning and grouping in the browser...</div>}
            {browserGroups.length > 0 && (
              <div className="muted" style={{ marginTop: '0.5rem' }}>
                Grouped into {browserGroups.length} message type(s); will upload {toUploadCount} sample file(s) (max{' '}
                {MAX_SAMPLES_DEFAULT} per group).
              </div>
            )}
          </div>
        )}

        {mode === 'zip' && (
          <div style={{ marginTop: '1rem' }}>
            <FileDropzone
              files={zipFiles}
              onFilesChange={setZipFiles}
              accept={['.zip']}
              multiple={false}
              label="Drop a ZIP file here"
              hint="One ZIP archive containing your sample XMLs."
            />
          </div>
        )}

        {mode === 'serverPath' && (
          <div className="field" style={{ marginTop: '1rem' }}>
            <label htmlFor="server-path">Server path</label>
            <input
              id="server-path"
              type="text"
              value={serverPath}
              onChange={(e) => setServerPath(e.target.value)}
              placeholder="/allowed/base/folder/samples"
            />
            <div className="hint">Must be inside a folder configured in app.allowed-base-folders on the server.</div>
          </div>
        )}
      </div>

      {mode === 'folder' && browserGroups.length > 0 && (
        <div className="panel">
          <div className="section-title">File preview</div>
          <div className="section-desc">Files were grouped by root element and namespace before anything is uploaded.</div>
          <table>
            <thead>
              <tr>
                <th></th>
                <th>Message type</th>
                <th>Namespace</th>
                <th>File count</th>
              </tr>
            </thead>
            <tbody>
              {browserGroups.map((g) => (
                <tr key={g.groupKey}>
                  <td>
                    <input type="checkbox" checked={selectedGroups.has(g.groupKey)} onChange={() => toggleGroup(g.groupKey)} />
                  </td>
                  <td>{g.rootElementName}</td>
                  <td className="muted">{g.namespace || `(no namespace, fingerprint ${g.fingerprint?.slice(0, 40)}...)`}</td>
                  <td>{g.files.length}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {browserBroken.length > 0 && (
            <div className="muted" style={{ marginTop: '0.5rem' }}>
              {browserBroken.length} file(s) couldn't be parsed and are excluded: {browserBroken.map((f) => f.name).join(', ')}
            </div>
          )}
        </div>
      )}

      <div className="panel">
        <div className="section-title">Baseline XSD (optional)</div>
        <div className="section-desc">
          Compare the newly inferred structure against a previously registered XSD, so the review highlights only what
          actually changed.
        </div>
        <SearchableSelect
          options={baselineOptions}
          value={baselineSchemaId}
          onChange={setBaselineSchemaId}
          onClear={() => setBaselineSchemaId('')}
          clearable
          placeholder="-- none --"
        />
        <div className="btn-row" style={{ marginTop: '0.5rem' }}>
          <label className="muted" style={{ cursor: baselineUploading ? 'default' : 'pointer' }}>
            <input
              type="file"
              accept=".xsd"
              style={{ display: 'none' }}
              disabled={baselineUploading}
              onChange={(e) => {
                const file = e.target.files?.[0]
                if (file) handleBaselineUpload(file)
                e.target.value = ''
              }}
            />
            <span className="link-button">{baselineUploading ? 'Uploading...' : 'or upload a new XSD to use as baseline'}</span>
          </label>
        </div>
        {baselineError && <div className="error-text">{baselineError}</div>}
      </div>

      <div className="panel">
        <div className="btn-row" style={{ marginTop: 0 }}>
          <button className="primary" onClick={handleCreateXsd} disabled={submitting || isRunning || !canSubmit}>
            {submitting ? 'Starting...' : 'Create XSD'}
          </button>
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
      </div>

      {serverGroups.length > 0 && (
        <div className="panel">
          <div className="section-title">Review</div>
          <div className="field">
            <label htmlFor="active-group">Group</label>
            <select id="active-group" value={activeGroup} onChange={(e) => setActiveGroup(e.target.value)}>
              {serverGroups.map((g) => (
                <option key={g.groupKey} value={g.groupKey}>
                  {g.rootElementName} ({g.fileNames.length} files)
                </option>
              ))}
            </select>
          </div>
          {brokenFiles.length > 0 && <div className="muted">{brokenFiles.length} broken/unparseable file(s), excluded.</div>}

          {review.length === 0 ? (
            <div className="muted">No doubtful items for this group -- every guess was high-confidence.</div>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Field path</th>
                  <th>Guess</th>
                  <th>Evidence</th>
                  <th>Confidence</th>
                  <th>Decision</th>
                </tr>
              </thead>
              <tbody>
                {review.map((item) => (
                  <tr key={item.path}>
                    <td>{item.path}</td>
                    <td className="muted">{item.guess}</td>
                    <td className="muted">{item.evidence}</td>
                    <td>
                      <span className={`badge ${item.confidence.toLowerCase()}`}>{item.confidence}</span>
                    </td>
                    <td>
                      <select
                        value={decisions[item.path] ?? ''}
                        onChange={(e) => setDecisions((prev) => ({ ...prev, [item.path]: e.target.value }))}
                      >
                        <option value="">-- pending --</option>
                        <option value="accept">Accept</option>
                        <option value="reject">Reject</option>
                      </select>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <div className="btn-row">
            <button onClick={acceptAll} disabled={review.length === 0}>
              Accept all suggestions
            </button>
            <button onClick={handleRebuild} disabled={review.length === 0}>
              Rebuild XSD
            </button>
            <button onClick={downloadReview}>Download report (JSON)</button>
          </div>

          {rebuildResult && (
            <div className="muted" style={{ marginTop: '0.5rem' }}>
              {rebuildResult.validSamples} of {rebuildResult.totalSamples} samples valid against the current XSD.
              <div className="hint">{rebuildResult.note}</div>
            </div>
          )}

          <div className="btn-row" style={{ marginTop: '1rem' }}>
            {jobId && (
              <a href={api.inferXsdUrl(jobId, activeGroup)}>
                <button>Download XSD</button>
              </a>
            )}
            <button onClick={handleRegister}>Register</button>
            {registered[activeGroup] ? (
              <span className="badge high">Registered as {registered[activeGroup].name}</span>
            ) : (
              <span className="badge draft">Draft - needs business confirmation</span>
            )}
          </div>
          {actionError && <div className="error-text">{actionError}</div>}
          {currentGroupInfo && <div className="muted" style={{ marginTop: '0.5rem' }}>{currentGroupInfo.fileNames.length} sample file(s) in this group.</div>}
        </div>
      )}
    </div>
  )
}
