/**
 * Pulls the first .xml entry out of a ZIP blob, for the Generate page's live preview -- just
 * enough of the ZIP format to read one small entry, using the browser's built-in
 * DecompressionStream for "deflate" entries so no zip library is needed.
 *
 * Reads entry metadata from the central directory (at the end of the archive), not from each
 * entry's own local file header: Java's ZipOutputStream writes deflate entries in the streaming
 * "data descriptor" format (general-purpose flag bit 3), which leaves the local header's
 * compressed/uncompressed size and CRC-32 fields as 0 -- the real values are deferred to a
 * descriptor written after the entry's data. The central directory always has the real sizes
 * regardless of which format the local headers use, which is exactly why standard zip tools
 * (and the JDK's own ZipFile) read it instead of walking local headers directly.
 */
export async function readFirstZipEntry(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer())
  const view = new DataView(bytes.buffer)

  const EOCD_SIGNATURE = 0x06054b50
  const CENTRAL_DIR_SIGNATURE = 0x02014b50
  // The EOCD record is 22 bytes plus an optional comment (up to 65535 bytes) -- scan backward
  // from the end for its signature rather than assuming a fixed offset.
  const searchStart = Math.max(0, bytes.length - 22 - 65557)
  let eocdOffset = -1
  for (let i = bytes.length - 22; i >= searchStart; i--) {
    if (view.getUint32(i, true) === EOCD_SIGNATURE) {
      eocdOffset = i
      break
    }
  }
  if (eocdOffset < 0) {
    throw new Error('Not a valid zip file (no end-of-central-directory record found)')
  }

  const totalEntries = view.getUint16(eocdOffset + 10, true)
  let cdOffset = view.getUint32(eocdOffset + 16, true)

  for (let i = 0; i < totalEntries; i++) {
    if (view.getUint32(cdOffset, true) !== CENTRAL_DIR_SIGNATURE) {
      break
    }
    const compressionMethod = view.getUint16(cdOffset + 10, true)
    const compressedSize = view.getUint32(cdOffset + 20, true)
    const fileNameLength = view.getUint16(cdOffset + 28, true)
    const extraLength = view.getUint16(cdOffset + 30, true)
    const commentLength = view.getUint16(cdOffset + 32, true)
    const localHeaderOffset = view.getUint32(cdOffset + 42, true)
    const name = new TextDecoder().decode(bytes.subarray(cdOffset + 46, cdOffset + 46 + fileNameLength))

    if (name.endsWith('.xml')) {
      // The local header's own filename/extra-length fields are reliable even in streaming
      // mode -- only its size/CRC fields are the ones deferred -- so they're used just to find
      // where this entry's data actually starts; the central directory's size says how much of it to read.
      const localFileNameLength = view.getUint16(localHeaderOffset + 26, true)
      const localExtraLength = view.getUint16(localHeaderOffset + 28, true)
      const dataStart = localHeaderOffset + 30 + localFileNameLength + localExtraLength
      const raw = bytes.subarray(dataStart, dataStart + compressedSize)

      if (compressionMethod === 0) {
        return new TextDecoder().decode(raw)
      }
      if (compressionMethod === 8) {
        const stream = new Blob([raw]).stream().pipeThrough(new DecompressionStream('deflate-raw'))
        const decompressed = await new Response(stream).arrayBuffer()
        return new TextDecoder().decode(decompressed)
      }
      throw new Error(`Unsupported zip compression method: ${compressionMethod}`)
    }

    cdOffset += 46 + fileNameLength + extraLength + commentLength
  }
  throw new Error('No .xml entry found in zip')
}
