import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { SchemaRecord, SchemaScanResult } from '../api/types'

export default function RegistryPage() {
  const [schemas, setSchemas] = useState<SchemaRecord[]>([])
  const [scans, setScans] = useState<Record<string, SchemaScanResult>>({})
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    refresh()
  }, [])

  function refresh() {
    api
      .listSchemas()
      .then((list) => {
        setSchemas(list)
        list.forEach((s) => {
          api
            .scanSchema(s.id)
            .then((scan) => setScans((prev) => ({ ...prev, [s.id]: scan })))
            .catch(() => {})
        })
      })
      .catch((e) => setError(String(e)))
  }

  function nameOf(id: string | null): string {
    if (!id) return ''
    return schemas.find((s) => s.id === id)?.name ?? id
  }

  return (
    <div>
      <h1>Registry</h1>
      {error && <div className="error-text">{error}</div>}
      <div className="panel">
        {schemas.length === 0 ? (
          <div className="muted">No schemas registered yet. Register one from the Generate or Create XSD page.</div>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Namespace</th>
                <th>Version</th>
                <th>Linked supplement</th>
                <th>Scan summary</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {schemas.map((s) => {
                const scan = scans[s.id]
                return (
                  <tr key={s.id}>
                    <td>{s.name}</td>
                    <td className="muted">{s.namespace || '(none)'}</td>
                    <td>{s.version ?? '-'}</td>
                    <td>{nameOf(s.linkedSupplementId) || '-'}</td>
                    <td className="muted">
                      {scan
                        ? `required=${scan.requiredCount} optional=${scan.optionalCount} choices=${scan.choiceCount} xs:any=${scan.anySlotCount}`
                        : 'scanning...'}
                    </td>
                    <td>
                      <a href={api.schemaDownloadUrl(s.id)}>
                        <button>Download</button>
                      </a>
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
