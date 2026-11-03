import { describe, expect, it } from 'vitest'
import { readFirstZipEntry } from './readFirstZipEntry'

// TS's typed-array generics (Uint8Array<ArrayBufferLike>) are stricter than lib.dom's BlobPart
// (which wants ArrayBufferView<ArrayBuffer> specifically) even though a Uint8Array has always
// been a valid Blob part at runtime -- a type-level-only mismatch, not a behavior change.
function asBlobPart(bytes: Uint8Array): BlobPart {
  return bytes as unknown as BlobPart
}

function crc32(buf: Uint8Array): number {
  const table: number[] = []
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    }
    table[n] = c >>> 0
  }
  let crc = 0xffffffff
  for (let i = 0; i < buf.length; i++) {
    crc = table[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8)
  }
  return (crc ^ 0xffffffff) >>> 0
}

async function deflateRaw(data: Uint8Array): Promise<Uint8Array> {
  const stream = new Blob([asBlobPart(data)]).stream().pipeThrough(new CompressionStream('deflate-raw'))
  return new Uint8Array(await new Response(stream).arrayBuffer())
}

function concat(parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((sum, p) => sum + p.length, 0))
  let offset = 0
  for (const p of parts) {
    out.set(p, offset)
    offset += p.length
  }
  return out
}

function u16(view: DataView, offset: number, value: number) {
  view.setUint16(offset, value, true)
}

function u32(view: DataView, offset: number, value: number) {
  view.setUint32(offset, value, true)
}

/** Builds a single-entry ZIP using the same "streaming / data descriptor" format Java's
 * ZipOutputStream writes for deflate entries by default: the local file header's CRC/compressed
 * size/uncompressed size fields are left at 0 (deferred to a data descriptor written after the
 * entry's data), with the real sizes only present in the central directory at the end. */
async function buildStreamingFormatZip(fileName: string, content: string): Promise<Uint8Array> {
  const nameBytes = new TextEncoder().encode(fileName)
  const uncompressed = new TextEncoder().encode(content)
  const compressed = await deflateRaw(uncompressed)
  const crc = crc32(uncompressed)

  const localHeader = new Uint8Array(30)
  const lv = new DataView(localHeader.buffer)
  u32(lv, 0, 0x04034b50)
  u16(lv, 4, 20)
  u16(lv, 6, 0x0008) // general purpose flag: bit 3 = data descriptor used
  u16(lv, 8, 8) // compression method: deflate
  u16(lv, 10, 0)
  u16(lv, 12, 0)
  u32(lv, 14, 0) // crc-32 -- deferred, 0 here (reproduces the real bug input)
  u32(lv, 18, 0) // compressed size -- deferred, 0 here
  u32(lv, 22, 0) // uncompressed size -- deferred, 0 here
  u16(lv, 26, nameBytes.length)
  u16(lv, 28, 0)

  const dataDescriptor = new Uint8Array(16)
  const dv = new DataView(dataDescriptor.buffer)
  u32(dv, 0, 0x08074b50)
  u32(dv, 4, crc)
  u32(dv, 8, compressed.length)
  u32(dv, 12, uncompressed.length)

  const localSection = concat([localHeader, nameBytes, compressed, dataDescriptor])
  const centralDirOffset = localSection.length

  const centralHeader = new Uint8Array(46)
  const cv = new DataView(centralHeader.buffer)
  u32(cv, 0, 0x02014b50)
  u16(cv, 4, 20)
  u16(cv, 6, 20)
  u16(cv, 8, 0x0008)
  u16(cv, 10, 8)
  u16(cv, 12, 0)
  u16(cv, 14, 0)
  u32(cv, 16, crc)
  u32(cv, 20, compressed.length) // the real size -- only here, not in the local header
  u32(cv, 24, uncompressed.length)
  u16(cv, 28, nameBytes.length)
  u16(cv, 30, 0)
  u16(cv, 32, 0)
  u16(cv, 34, 0)
  u16(cv, 36, 0)
  u32(cv, 38, 0)
  u32(cv, 42, 0) // local header offset

  const centralSection = concat([centralHeader, nameBytes])
  const centralDirSize = centralSection.length

  const eocd = new Uint8Array(22)
  const ev = new DataView(eocd.buffer)
  u32(ev, 0, 0x06054b50)
  u16(ev, 4, 0)
  u16(ev, 6, 0)
  u16(ev, 8, 1)
  u16(ev, 10, 1)
  u32(ev, 12, centralDirSize)
  u32(ev, 16, centralDirOffset)
  u16(ev, 20, 0)

  return concat([localSection, centralSection, eocd])
}

describe('readFirstZipEntry', () => {
  it("reads a deflate entry even when the local header's size/CRC are 0 (ZipOutputStream's streaming format)", async () => {
    const xml = '<?xml version="1.0"?><root><value>hello</value></root>'
    const zipBytes = await buildStreamingFormatZip('Document_small_1.xml', xml)
    const text = await readFirstZipEntry(new Blob([asBlobPart(zipBytes)]))
    expect(text).toBe(xml)
  })

  it('throws a clear error when no .xml entry is present', async () => {
    const zipBytes = await buildStreamingFormatZip('report.json', '{}')
    await expect(readFirstZipEntry(new Blob([asBlobPart(zipBytes)]))).rejects.toThrow('No .xml entry found')
  })
})
