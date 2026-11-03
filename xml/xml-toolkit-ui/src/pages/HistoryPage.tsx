import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { HistoryEntry, JobStatusResponse } from '../api/types'

export default function HistoryPage() {
  const [entries, setEntries] = useState<HistoryEntry[]>([])
  const [statuses, setStatuses] = useState<Record<string, JobStatusResponse>>({})
  const [error, setError] = useState<string | null>(null)
  const [rerunError, setRerunError] = useState<string | null>(null)
  const [rerunning, setRerunning] = useState<string | null>(null)

  useEffect(() => {
    refresh()
  }, [])

  function refresh() {
    api
      .history()
      .then((list) => {
        setEntries(list)
        list.forEach((e) => {
          api
            .jobStatus(e.jobId)
            .then((s) => setStatuses((prev) => ({ ...prev, [e.jobId]: s })))
            .catch(() => {})
        })
      })
      .catch((e) => setError(String(e)))
  }

  async function handleRerun(id: string) {
    setRerunning(id)
    setRerunError(null)
    try {
      await api.rerun(id)
      refresh()
    } catch (e) {
      setRerunError(String(e))
    } finally {
      setRerunning(null)
    }
  }

  return (
    <div>
      <h1>History</h1>
      {error && <div className="error-text">{error}</div>}
      {rerunError && <div className="error-text">{rerunError}</div>}
      <div className="panel">
        {entries.length === 0 ? (
          <div className="muted">No runs yet. Generate or infer something and it'll show up here.</div>
        ) : (
          <table>
            <thead>
              <tr>
                <th>When</th>
                <th>Type</th>
                <th>Settings</th>
                <th>Status</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {entries.map((e) => {
                const status = statuses[e.jobId]
                return (
                  <tr key={e.id}>
                    <td className="muted">{new Date(e.createdAt).toLocaleString()}</td>
                    <td>{e.type}</td>
                    <td className="muted">
                      {e.request
                        ? `count=${e.request.count ?? 1000} level=${e.request.level ?? 3} size=${e.request.size ?? 'mixed'} seed=${
                            e.request.seed ?? '(random)'
                          }`
                        : '(infer input not retained)'}
                    </td>
                    <td>{status?.status ?? 'loading...'}</td>
                    <td>
                      <div className="btn-row" style={{ marginTop: 0 }}>
                        {status?.status === 'SUCCEEDED' && (
                          <a href={api.jobDownloadUrl(e.jobId)}>
                            <button>Download</button>
                          </a>
                        )}
                        {e.type === 'generate' && (
                          <button onClick={() => handleRerun(e.id)} disabled={rerunning === e.id}>
                            {rerunning === e.id ? 'Re-running...' : 'Re-run'}
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
