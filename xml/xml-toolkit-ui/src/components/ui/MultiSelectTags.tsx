import { useEffect, useRef, useState } from 'react'

export interface MultiOption {
  value: string
  label: string
  description?: string
  disabled?: boolean
  disabledReason?: string
}

interface Props {
  id?: string
  options: MultiOption[]
  selected: string[]
  onChange: (next: string[]) => void
  placeholder?: string
  searchable?: boolean
}

/** A searchable multi-select dropdown: a checklist panel (filterable, with Select all / Clear
 * all) to pick several values, with the current selection shown as removable tag chips in the
 * trigger itself, plus a running count of how many are selected. */
export default function MultiSelectTags({
  id,
  options,
  selected,
  onChange,
  placeholder = 'Select...',
  searchable = true,
}: Props) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    function onDocClick(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false)
        setQuery('')
      }
    }
    document.addEventListener('mousedown', onDocClick)
    return () => document.removeEventListener('mousedown', onDocClick)
  }, [])

  function toggle(opt: MultiOption) {
    if (opt.disabled) return
    onChange(selected.includes(opt.value) ? selected.filter((v) => v !== opt.value) : [...selected, opt.value])
  }

  function remove(value: string, e: React.MouseEvent) {
    e.stopPropagation()
    onChange(selected.filter((v) => v !== value))
  }

  function selectAll() {
    onChange(options.filter((o) => !o.disabled).map((o) => o.value))
  }

  function clearAll() {
    onChange([])
  }

  const selectedOptions = selected.map((v) => options.find((o) => o.value === v)).filter((o): o is MultiOption => !!o)
  const filtered = query.trim()
    ? options.filter(
        (o) =>
          o.label.toLowerCase().includes(query.trim().toLowerCase()) ||
          o.description?.toLowerCase().includes(query.trim().toLowerCase()),
      )
    : options

  return (
    <div className="ms-root" ref={rootRef} id={id}>
      <div className="ms-trigger" onClick={() => setOpen((o) => !o)}>
        {selectedOptions.length === 0 ? (
          <span className="ss-placeholder">{placeholder}</span>
        ) : (
          <div className="ms-tags">
            {selectedOptions.map((opt) => (
              <span className="tag" key={opt.value}>
                {opt.label}
                <span className="tag-remove" role="button" aria-label={`Remove ${opt.label}`} onClick={(e) => remove(opt.value, e)}>
                  ×
                </span>
              </span>
            ))}
          </div>
        )}
        <span className="ms-trigger-right">
          {selectedOptions.length > 0 && <span className="ms-count">{selectedOptions.length} selected</span>}
          <svg className="ss-chevron" width="10" height="6" viewBox="0 0 10 6" fill="none">
            <path d="M1 1L5 5L9 1" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </span>
      </div>

      {open && (
        <div className="ss-menu">
          <div className="ms-menu-actions">
            <button type="button" className="link-button" onClick={selectAll} disabled={options.every((o) => o.disabled)}>
              Select all
            </button>
            <span className="ms-menu-actions-sep">·</span>
            <button type="button" className="link-button" onClick={clearAll} disabled={selected.length === 0}>
              Clear all
            </button>
          </div>
          {searchable && (
            <input
              autoFocus
              className="ss-search"
              type="text"
              placeholder="Search..."
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          )}
          <div className="ss-options">
            {filtered.length === 0 && <div className="ss-empty">No matches</div>}
            {filtered.map((opt) => (
              <label
                key={opt.value}
                className={`ms-option${opt.disabled ? ' ms-option-disabled' : ''}`}
                title={opt.disabled ? opt.disabledReason : undefined}
              >
                <input
                  type="checkbox"
                  checked={selected.includes(opt.value)}
                  disabled={opt.disabled}
                  onChange={() => toggle(opt)}
                />
                <div>
                  <div className="ss-option-label">{opt.label}</div>
                  {opt.description && <div className="ss-option-desc">{opt.description}</div>}
                  {opt.disabled && opt.disabledReason && <div className="ss-option-desc">{opt.disabledReason}</div>}
                </div>
              </label>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
