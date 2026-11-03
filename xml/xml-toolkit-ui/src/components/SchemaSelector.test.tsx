import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import SchemaSelector from './SchemaSelector'
import { api } from '../api/client'
import type { SchemaRecord } from '../api/types'

vi.mock('../api/client', () => ({
  api: {
    listSchemas: vi.fn(),
    uploadSchema: vi.fn(),
  },
}))

const SCHEMAS: SchemaRecord[] = [
  { id: 'id-1', name: 'seev.035', fileName: 'id-1.xsd', rootElementName: 'Document', namespace: 'urn:ns:seev035', version: '001.16', linkedSupplementId: null },
  { id: 'id-2', name: 'supl.009', fileName: 'id-2.xsd', rootElementName: 'Document', namespace: 'urn:ns:supl009', version: '001.11', linkedSupplementId: null },
]

describe('SchemaSelector', () => {
  beforeEach(() => {
    vi.mocked(api.listSchemas).mockResolvedValue(SCHEMAS)
  })

  it('lists registered schemas and reports the selection', async () => {
    const onChange = vi.fn()
    render(<SchemaSelector value="" onChange={onChange} />)

    await waitFor(() => expect(screen.getByText(/seev\.035/)).toBeInTheDocument())

    fireEvent.change(screen.getByLabelText('XSD'), { target: { value: 'id-2' } })

    expect(onChange).toHaveBeenCalledWith('id-2', SCHEMAS[1])
  })

  it('shows an error message if the schema list fails to load', async () => {
    vi.mocked(api.listSchemas).mockRejectedValue(new Error('network down'))
    render(<SchemaSelector value="" onChange={vi.fn()} />)

    await waitFor(() => expect(screen.getByText(/network down/)).toBeInTheDocument())
  })
})
