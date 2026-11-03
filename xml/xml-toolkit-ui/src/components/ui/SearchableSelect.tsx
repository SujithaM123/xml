import { useEffect, useRef, useState } from 'react'

export interface SelectOption {
  value: string
  label: string
  description?: string
  disabled?: boolean
  disabledReason?: string
}

interface Props {
  id?: string
  options: SelectOption[]
  value: string
  onChange: (value: string) => void
  placeholder?: string
  searchable?: boolean
  clearable?: boolean
  onClear?: () => void
  disabled?: boolean
}

/** A styled single-select combobox: click to open, type to filter, click an option to pick it.
 * Used anywhere a plain <select> isn't expressive enough (option descriptions, disabled-with-reason,
 * search over many registered schemas). */
export default function SearchableSelect({
  id,
  options,
  value,
  onChange,
  placeholder = 'Select...',
  searchable = true,
  clearable = false,
  onClear,
  disabled = false,
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

  const selected = options.find((o) => o.value === value) ?? null
  const filtered = query.trim()
    ? options.filter((o) => o.label.toLowerCase().includes(query.trim().toLowerCase()))
    : options

  function pick(opt: SelectOption) {
    if (opt.disabled) return
    onChange(opt.value)
    setOpen(false)
    setQuery('')
  }

  return (
    <div className={`ss-root${disabled ? ' ss-disabled' : ''}`} ref={rootRef} id={id}>
      <button
        type="button"
        className={`ss-trigger${open ? ' ss-trigger-open' : ''}`}
        onClick={() => !disabled && setOpen((o) => !o)}
        disabled={disabled}
      >
        <span className={selected ? 'ss-value' : 'ss-placeholder'}>{selected ? selected.label : placeholder}</span>
        <span className="ss-trigger-icons">
          {clearable && selected && (
            <span
              className="ss-clear"
              role="button"
              aria-label="Clear selection"
              onClick={(e) => {
                e.stopPropagation()
                onClear?.()
                setOpen(false)
              }}
            >
              ×
            </span>
          )}
          <svg className="ss-chevron" width="10" height="6" viewBox="0 0 10 6" fill="none">
            <path d="M1 1L5 5L9 1" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </span>
      </button>

      {open && (
        <div className="ss-menu">
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
              <div
                key={opt.value}
                className={`ss-option${opt.value === value ? ' ss-option-selected' : ''}${opt.disabled ? ' ss-option-disabled' : ''}`}
                title={opt.disabled ? opt.disabledReason : undefined}
                onClick={() => pick(opt)}
              >
                <div className="ss-option-label">{opt.label}</div>
                {opt.description && <div className="ss-option-desc">{opt.description}</div>}
                {opt.disabled && opt.disabledReason && <div className="ss-option-desc">{opt.disabledReason}</div>}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
