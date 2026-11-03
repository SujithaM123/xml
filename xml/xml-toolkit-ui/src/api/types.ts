export interface SchemaRecord {
  id: string
  name: string
  fileName: string
  rootElementName: string
  namespace: string
  version: string | null
  linkedSupplementId: string | null
}

export interface SchemaScanResult {
  requiredCount: number
  optionalCount: number
  repeatingCount: number
  finiteRepeatCount: number
  choiceCount: number
  enumerationCount: number
  patternCount: number
  maxLengthCount: number
  anySlotCount: number
  maxDepth: number
  possibleLevels: number[]
  possibleErrorTypes: string[]
}

export type JobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface JobStatusResponse {
  jobId: string
  type: string
  status: JobStatus
  progressPercent: number
  errorMessage: string
  createdAt: string
}

export interface GenerateRequest {
  schemaId: string
  count?: number
  level?: number
  size?: string
  errorPercent?: number
  errorTypes?: string[]
  seed?: number
  threads?: number
  output?: string
  includeSupplement?: boolean
}

export interface GenerateReport {
  settings: Record<string, unknown>
  seed: number
  counts: {
    totalRequested: number
    valid: number
    invalid: number
    errorByType: Record<string, number>
  }
  sizeBytes: { min: number; avg: number; max: number }
  timeSeconds: number
  filesPerSecond: number
}

export interface FileGroup {
  groupKey: string
  namespace: string
  rootElementName: string
  fingerprint: string | null
  fileNames: string[]
}

export interface InferGroupsResponse {
  groups: FileGroup[]
  brokenFiles: string[]
  unknownFiles: string[]
}

export interface ReviewItem {
  path: string
  kind: string
  guess: string
  evidence: string
  confidence: 'HIGH' | 'MEDIUM' | 'LOW'
}

export interface ReviewResponse {
  fileCount: number
  needsReview: ReviewItem[]
  autoAccepted: ReviewItem[]
}

export interface HistoryEntry {
  id: string
  type: string
  jobId: string
  createdAt: string
  request: GenerateRequest | null
}
