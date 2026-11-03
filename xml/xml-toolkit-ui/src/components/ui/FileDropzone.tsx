import { useRef, useState } from 'react'

interface Props {
  files: File[]
  onFilesChange: (files: File[]) => void
  accept: string[]
  multiple?: boolean
  allowFolderPick?: boolean
  label: string
  hint?: string
  disabled?: boolean
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function matchesAccept(fileName: string, accept: string[]): boolean {
  const lower = fileName.toLowerCase()
  return accept.some((ext) => lower.endsWith(ext.toLowerCase()))
}

/** A drag-and-drop file picker: drop files or browse for them, see what's selected (name, size,
 * remove individually or clear all), and get a clear message about anything rejected for not
 * matching the accepted extension(s). Used for both "upload XML files" and "upload a ZIP". */
export default function FileDropzone({
  files,
  onFilesChange,
  accept,
  multiple = true,
  allowFolderPick = false,
  label,
  hint,
  disabled = false,
}: Props) {
  const [dragOver, setDragOver] = useState(false)
  const [rejected, setRejected] = useState<string[]>([])
  const inputRef = useRef<HTMLInputElement>(null)
  const folderInputRef = useRef<HTMLInputElement>(null)

  function ingest(incoming: File[]) {
    const accepted = incoming.filter((f) => matchesAccept(f.name, accept))
    const bad = incoming.filter((f) => !matchesAccept(f.name, accept)).map((f) => f.name)
    setRejected(bad)

    if (!multiple) {
      onFilesChange(accepted.slice(0, 1))
      return
    }
    const key = (f: File) => `${f.name}:${f.size}:${f.lastModified}`
    const existingKeys = new Set(files.map(key))
    const merged = [...files, ...accepted.filter((f) => !existingKeys.has(key(f)))]
    onFilesChange(merged)
  }

  function handleDrop(e: React.DragEvent) {
    e.preventDefault()
    setDragOver(false)
    if (disabled) return
    ingest(Array.from(e.dataTransfer.files))
  }

  function removeFile(index: number) {
    onFilesChange(files.filter((_, i) => i !== index))
  }

  const totalSize = files.reduce((sum, f) => sum + f.size, 0)

  return (
    <div className="dropzone-wrap">
      <div
        className={`dropzone${dragOver ? ' dropzone-over' : ''}${disabled ? ' dropzone-disabled' : ''}`}
        onDragOver={(e) => {
          e.preventDefault()
          if (!disabled) setDragOver(true)
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
      >
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" className="dropzone-icon">
          <path
            d="M12 15V4m0 0L8 8m4-4l4 4M5 16v2a2 2 0 002 2h10a2 2 0 002-2v-2"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
        <div className="dropzone-text">
          <strong>{label}</strong>
          <span>Drag and drop here, or</span>
        </div>
        <div className="btn-row" style={{ marginTop: '0.5rem' }}>
          <button type="button" onClick={() => inputRef.current?.click()} disabled={disabled}>
            Browse files
          </button>
          {allowFolderPick && (
            <button type="button" onClick={() => folderInputRef.current?.click()} disabled={disabled}>
              Choose a folder
            </button>
          )}
        </div>
        {hint && <div className="hint">{hint}</div>}
        <input
          ref={inputRef}
          type="file"
          multiple={multiple}
          accept={accept.join(',')}
          style={{ display: 'none' }}
          disabled={disabled}
          onChange={(e) => {
            if (e.target.files) ingest(Array.from(e.target.files))
            e.target.value = ''
          }}
        />
        {allowFolderPick && (
          <input
            ref={folderInputRef}
            type="file"
            multiple
            // @ts-expect-error webkitdirectory isn't in the TS DOM lib yet
            webkitdirectory="true"
            style={{ display: 'none' }}
            disabled={disabled}
            onChange={(e) => {
              if (e.target.files) ingest(Array.from(e.target.files))
              e.target.value = ''
            }}
          />
        )}
      </div>

      {rejected.length > 0 && (
        <div className="error-text" style={{ marginTop: '0.5rem' }}>
          Skipped {rejected.length} file(s) not matching {accept.join(', ')}: {rejected.slice(0, 5).join(', ')}
          {rejected.length > 5 ? '...' : ''}
        </div>
      )}

      {files.length > 0 && (
        <div className="file-list">
          <div className="file-list-header">
            <span>
              {files.length} file{files.length === 1 ? '' : 's'} selected · {formatSize(totalSize)} total
            </span>
            <button type="button" className="link-button" onClick={() => onFilesChange([])}>
              Clear all
            </button>
          </div>
          <ul>
            {files.slice(0, 50).map((f, i) => (
              <li key={`${f.name}:${f.size}:${f.lastModified}`} className="file-chip">
                <span className="file-chip-name">{f.name}</span>
                <span className="file-chip-size">{formatSize(f.size)}</span>
                <span className="file-chip-remove" role="button" aria-label={`Remove ${f.name}`} onClick={() => removeFile(i)}>
                  ×
                </span>
              </li>
            ))}
          </ul>
          {files.length > 50 && <div className="muted">...and {files.length - 50} more</div>}
        </div>
      )}
    </div>
  )
}
