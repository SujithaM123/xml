import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { SchemaRecord } from '../api/types'

interface Props {
  value: string
  onChange: (schemaId: string, schema: SchemaRecord | null) => void
}

/** Pick a schema already in the registry, or upload a new XSD right from here -- used by both
 * the Generate and Create XSD pages (C1.1's "Select XSD (from registry, or upload)"). */
export default function SchemaSelector({ value, onChange }: Props) {
  const [schemas, setSchemas] = useState<SchemaRecord[]>([])
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    refresh()
  }, [])

  function refresh() {
    api
      .listSchemas()
      .then(setSchemas)
      .catch((e) => setError(String(e)))
  }

  function handleSelect(id: string) {
    const schema = schemas.find((s) => s.id === id) ?? null
    onChange(id, schema)
  }

  async function handleUpload(file: File) {
    setUploading(true)
    setError(null)
    try {
      const record = await api.uploadSchema(file)
      setSchemas((prev) => [...prev, record])
      onChange(record.id, record)
    } catch (e) {
      setError(String(e))
    } finally {
      setUploading(false)
    }
  }

  return (
    <div className="field">
      <label htmlFor="schema-select">XSD</label>
      <select id="schema-select" value={value} onChange={(e) => handleSelect(e.target.value)}>
        <option value="">-- select a registered schema --</option>
        {schemas.map((s) => (
          <option key={s.id} value={s.id}>
            {s.name} {s.version ? `(${s.version})` : ''}
          </option>
        ))}
      </select>
      <div className="btn-row">
        <label className="muted" style={{ cursor: uploading ? 'default' : 'pointer' }}>
          <input
            type="file"
            accept=".xsd"
            style={{ display: 'none' }}
            disabled={uploading}
            onChange={(e) => {
              const file = e.target.files?.[0]
              if (file) handleUpload(file)
              e.target.value = ''
            }}
          />
          <span style={{ textDecoration: 'underline' }}>{uploading ? 'Uploading...' : 'or upload a new XSD'}</span>
        </label>
      </div>
      {error && <div className="error-text">{error}</div>}
    </div>
  )
}
