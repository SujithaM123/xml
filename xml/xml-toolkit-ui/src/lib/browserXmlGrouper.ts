/**
 * Groups a folder of sample XMLs by root element + namespace (or a structure fingerprint when
 * there's no namespace) entirely in the browser, before anything is uploaded -- so a user who
 * picks a folder with 50,000 files only ever sends the server a representative sample (C2).
 * Mirrors the server-side FileGrouper's logic exactly (same key scheme), just running on
 * File/DOMParser instead of Path/StAX.
 */

export interface BrowserFileGroup {
  groupKey: string
  namespace: string
  rootElementName: string
  fingerprint: string | null
  files: File[]
}

export interface BrowserGroupResult {
  groups: BrowserFileGroup[]
  brokenFiles: File[]
}

export async function groupFilesInBrowser(files: File[]): Promise<BrowserGroupResult> {
  const byKey = new Map<string, BrowserFileGroup>()
  const broken: File[] = []

  for (const file of files) {
    try {
      const peek = await peekRoot(file)
      const key = peek.namespace ? `${peek.namespace}:${peek.rootName}` : `${peek.rootName}#${peek.fingerprint}`
      let group = byKey.get(key)
      if (!group) {
        group = {
          groupKey: key,
          namespace: peek.namespace,
          rootElementName: peek.rootName,
          fingerprint: peek.namespace ? null : peek.fingerprint,
          files: [],
        }
        byKey.set(key, group)
      }
      group.files.push(file)
    } catch {
      broken.push(file)
    }
  }

  return { groups: Array.from(byKey.values()), brokenFiles: broken }
}

async function peekRoot(file: File): Promise<{ namespace: string; rootName: string; fingerprint: string }> {
  const text = await file.text()
  const doc = new DOMParser().parseFromString(text, 'application/xml')
  if (doc.getElementsByTagName('parsererror').length > 0) {
    throw new Error(`${file.name} is not well-formed XML`)
  }
  const root = doc.documentElement
  if (!root) {
    throw new Error(`${file.name} has no root element`)
  }
  const childNames = new Set<string>()
  for (const child of Array.from(root.children)) {
    childNames.add(child.localName)
  }
  return {
    namespace: root.namespaceURI ?? '',
    rootName: root.localName,
    fingerprint: Array.from(childNames).sort().join(','),
  }
}

/** Evenly-spaced sample across the whole list (not just the first N) so the sample isn't biased
 * toward whatever a directory listing happened to return first. */
export function smartSample(files: File[], maxSamples = 200): File[] {
  if (files.length <= maxSamples) {
    return files
  }
  const step = files.length / maxSamples
  const sampled: File[] = []
  for (let i = 0; i < maxSamples; i++) {
    sampled.push(files[Math.floor(i * step)])
  }
  return sampled
}
