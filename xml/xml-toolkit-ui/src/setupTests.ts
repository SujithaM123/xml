import '@testing-library/jest-dom'

// jsdom's Blob/File polyfill doesn't implement .text() (a standard Web API every real browser
// has had for years) -- this patches it in for tests only; production code never needs it since
// a real browser's File already has the method. The `'text' in Blob.prototype` form of this
// check makes TypeScript narrow the assignment target's type to `never` (lib.dom.d.ts says the
// method always exists), so the runtime check here is a plain typeof instead.
if (typeof Blob !== 'undefined' && typeof Blob.prototype.text !== 'function') {
  ;(Blob.prototype as { text?: () => Promise<string> }).text = function (this: Blob): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.onerror = () => reject(reader.error)
      reader.readAsText(this)
    })
  }
}

// Same jsdom gap, same fix, for .arrayBuffer() (used by readFirstZipEntry's tests).
if (typeof Blob !== 'undefined' && typeof Blob.prototype.arrayBuffer !== 'function') {
  ;(Blob.prototype as { arrayBuffer?: () => Promise<ArrayBuffer> }).arrayBuffer = function (this: Blob): Promise<ArrayBuffer> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result as ArrayBuffer)
      reader.onerror = () => reject(reader.error)
      reader.readAsArrayBuffer(this)
    })
  }
}

// Same jsdom gap, same fix, for .stream() (used by readFirstZipEntry to decompress a deflate
// entry via DecompressionStream).
if (typeof Blob !== 'undefined' && typeof Blob.prototype.stream !== 'function') {
  ;(Blob.prototype as { stream?: () => ReadableStream }).stream = function (this: Blob): ReadableStream {
    const blob = this
    return new ReadableStream({
      async start(controller) {
        controller.enqueue(new Uint8Array(await blob.arrayBuffer()))
        controller.close()
      },
    })
  }
}
