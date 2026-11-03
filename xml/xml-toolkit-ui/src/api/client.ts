import type {
  SchemaRecord,
  SchemaScanResult,
  JobStatusResponse,
  GenerateRequest,
  GenerateReport,
  InferGroupsResponse,
  ReviewResponse,
  HistoryEntry,
} from './types'

const BASE = '/api'

async function asJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`
    try {
      const body = await response.text()
      if (body) message = body
    } catch {
      // keep the status-line message
    }
    throw new Error(message)
  }
  return response.json() as Promise<T>
}

export const api = {
  listSchemas: (): Promise<SchemaRecord[]> => fetch(`${BASE}/schemas`).then((r) => asJson(r)),

  uploadSchema: (file: File, name?: string, linkedSupplementId?: string): Promise<SchemaRecord> => {
    const form = new FormData()
    form.append('file', file)
    if (name) form.append('name', name)
    if (linkedSupplementId) form.append('linkedSupplementId', linkedSupplementId)
    return fetch(`${BASE}/schemas`, { method: 'POST', body: form }).then((r) => asJson(r))
  },

  scanSchema: (id: string): Promise<SchemaScanResult> => fetch(`${BASE}/schemas/${id}/scan`).then((r) => asJson(r)),

  schemaDownloadUrl: (id: string): string => `${BASE}/schemas/${id}/download`,

  generate: (req: GenerateRequest): Promise<{ jobId: string }> =>
    fetch(`${BASE}/generate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    }).then((r) => asJson(r)),

  jobStatus: (jobId: string): Promise<JobStatusResponse> => fetch(`${BASE}/jobs/${jobId}`).then((r) => asJson(r)),

  cancelJob: (jobId: string): Promise<{ jobId: string; cancelled: boolean }> =>
    fetch(`${BASE}/jobs/${jobId}/cancel`, { method: 'POST' }).then((r) => asJson(r)),

  jobDownloadUrl: (jobId: string): string => `${BASE}/jobs/${jobId}/download`,

  jobReport: (jobId: string, format: 'json' | 'csv'): Promise<string> =>
    fetch(`${BASE}/jobs/${jobId}/report?format=${format}`).then((r) => {
      if (!r.ok) throw new Error(`${r.status} ${r.statusText}`)
      return r.text()
    }),

  jobReportJson: (jobId: string): Promise<GenerateReport> =>
    fetch(`${BASE}/jobs/${jobId}/report?format=json`).then((r) => asJson(r)),

  jobEventsUrl: (jobId: string): string => `${BASE}/jobs/${jobId}/events`,

  inferFromZip: (zip: File, opts: { baselineSchemaId?: string; maxSamples?: number } = {}): Promise<{ jobId: string }> => {
    const form = new FormData()
    form.append('zip', zip)
    if (opts.baselineSchemaId) form.append('baselineSchemaId', opts.baselineSchemaId)
    if (opts.maxSamples) form.append('maxSamples', String(opts.maxSamples))
    return fetch(`${BASE}/infer`, { method: 'POST', body: form }).then((r) => asJson(r))
  },

  inferFromFiles: (files: File[], opts: { baselineSchemaId?: string; maxSamples?: number } = {}): Promise<{ jobId: string }> => {
    const form = new FormData()
    files.forEach((f) => form.append('files', f))
    if (opts.baselineSchemaId) form.append('baselineSchemaId', opts.baselineSchemaId)
    if (opts.maxSamples) form.append('maxSamples', String(opts.maxSamples))
    return fetch(`${BASE}/infer`, { method: 'POST', body: form }).then((r) => asJson(r))
  },

  inferFromServerPath: (serverPath: string, opts: { baselineSchemaId?: string; maxSamples?: number } = {}): Promise<{ jobId: string }> => {
    const form = new FormData()
    form.append('serverPath', serverPath)
    if (opts.baselineSchemaId) form.append('baselineSchemaId', opts.baselineSchemaId)
    if (opts.maxSamples) form.append('maxSamples', String(opts.maxSamples))
    return fetch(`${BASE}/infer`, { method: 'POST', body: form }).then((r) => asJson(r))
  },

  inferGroups: (jobId: string): Promise<InferGroupsResponse> => fetch(`${BASE}/infer/${jobId}/groups`).then((r) => asJson(r)),

  inferXsdUrl: (jobId: string, group: string): string => `${BASE}/infer/${jobId}/xsd?group=${encodeURIComponent(group)}`,

  inferReview: (jobId: string, group?: string): Promise<ReviewResponse> =>
    fetch(`${BASE}/infer/${jobId}/review${group ? `?group=${encodeURIComponent(group)}` : ''}`).then((r) => asJson(r)),

  inferDecisions: (
    jobId: string,
    group: string,
    decisions: Record<string, string>,
  ): Promise<{ group: string; validSamples: number; totalSamples: number; note: string }> =>
    fetch(`${BASE}/infer/${jobId}/decisions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ group, decisions }),
    }).then((r) => asJson(r)),

  inferRegister: (jobId: string, group: string, name?: string): Promise<SchemaRecord> => {
    const params = new URLSearchParams({ group })
    if (name) params.set('name', name)
    return fetch(`${BASE}/infer/${jobId}/register?${params}`, { method: 'POST' }).then((r) => asJson(r))
  },

  history: (): Promise<HistoryEntry[]> => fetch(`${BASE}/history`).then((r) => asJson(r)),

  rerun: (historyId: string): Promise<{ jobId: string }> =>
    fetch(`${BASE}/history/${historyId}/rerun`, { method: 'POST' }).then((r) => asJson(r)),
}
