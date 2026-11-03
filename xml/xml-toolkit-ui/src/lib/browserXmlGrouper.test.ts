import { describe, it, expect } from 'vitest'
import { groupFilesInBrowser, smartSample } from './browserXmlGrouper'

function xmlFile(name: string, content: string): File {
  return new File([content], name, { type: 'application/xml' })
}

describe('groupFilesInBrowser', () => {
  it('groups files by namespace + root element', async () => {
    const files = [
      xmlFile('a.xml', '<Document xmlns="urn:ns:a"><Foo/></Document>'),
      xmlFile('b.xml', '<Document xmlns="urn:ns:a"><Foo/></Document>'),
      xmlFile('c.xml', '<Document xmlns="urn:ns:b"><Bar/></Document>'),
    ]

    const result = await groupFilesInBrowser(files)

    expect(result.groups).toHaveLength(2)
    expect(result.brokenFiles).toHaveLength(0)
    const nsA = result.groups.find((g) => g.namespace === 'urn:ns:a')
    expect(nsA?.files.map((f) => f.name).sort()).toEqual(['a.xml', 'b.xml'])
  })

  it('uses a child-name fingerprint to separate same-named-root files with no namespace', async () => {
    const files = [
      xmlFile('a.xml', '<Root><Foo/><Bar/></Root>'),
      xmlFile('b.xml', '<Root><Baz/></Root>'),
    ]

    const result = await groupFilesInBrowser(files)

    expect(result.groups).toHaveLength(2)
    expect(result.groups.every((g) => g.namespace === '')).toBe(true)
    expect(result.groups.every((g) => g.fingerprint !== null)).toBe(true)
  })

  it('puts unparseable files in brokenFiles instead of throwing', async () => {
    const files = [xmlFile('good.xml', '<Root/>'), xmlFile('bad.xml', '<Root><unterminated>')]

    const result = await groupFilesInBrowser(files)

    expect(result.groups).toHaveLength(1)
    expect(result.brokenFiles.map((f) => f.name)).toEqual(['bad.xml'])
  })
})

describe('smartSample', () => {
  it('returns every file when under the limit', () => {
    const files = Array.from({ length: 5 }, (_, i) => xmlFile(`${i}.xml`, '<a/>'))
    expect(smartSample(files, 200)).toHaveLength(5)
  })

  it('caps at maxSamples and spreads evenly rather than taking just the first N', () => {
    const files = Array.from({ length: 1000 }, (_, i) => xmlFile(`${i}.xml`, '<a/>'))
    const sampled = smartSample(files, 200)
    expect(sampled).toHaveLength(200)
    // An even spread should include files from near the end, not just indices 0-199.
    const indices = sampled.map((f) => Number(f.name.replace('.xml', '')))
    expect(Math.max(...indices)).toBeGreaterThan(900)
  })
})
