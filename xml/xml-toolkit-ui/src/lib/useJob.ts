import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import type { JobStatus } from '../api/types'

export interface JobState {
  status: JobStatus | null
  progressPercent: number
  errorMessage: string
}

/** Tracks one job's live status via SSE (GET /api/jobs/{id}/events); falls back to polling if
 * the stream errors out (e.g. a proxy that buffers/doesn't support it), so the UI never just
 * stalls silently. */
export function useJob(jobId: string | null): JobState {
  const [state, setState] = useState<JobState>({ status: null, progressPercent: 0, errorMessage: '' })
  const pollRef = useRef<number | null>(null)

  useEffect(() => {
    if (!jobId) {
      setState({ status: null, progressPercent: 0, errorMessage: '' })
      return
    }

    let stopped = false
    const applyStatus = (data: { status: JobStatus; progressPercent: number; errorMessage?: string }) => {
      if (stopped) return
      setState({ status: data.status, progressPercent: data.progressPercent, errorMessage: data.errorMessage ?? '' })
    }

    const startPolling = () => {
      if (pollRef.current) return
      pollRef.current = window.setInterval(async () => {
        try {
          const s = await api.jobStatus(jobId)
          applyStatus(s)
          if (s.status === 'SUCCEEDED' || s.status === 'FAILED' || s.status === 'CANCELLED') {
            stopPolling()
          }
        } catch {
          // keep trying; a transient network blip shouldn't kill progress tracking
        }
      }, 1000)
    }
    const stopPolling = () => {
      if (pollRef.current) {
        window.clearInterval(pollRef.current)
        pollRef.current = null
      }
    }

    const source = new EventSource(api.jobEventsUrl(jobId))
    source.onmessage = (ev) => {
      try {
        applyStatus(JSON.parse(ev.data))
      } catch {
        // ignore a malformed event rather than crash the whole tracker
      }
    }
    source.onerror = () => {
      source.close()
      startPolling()
    }

    return () => {
      stopped = true
      source.close()
      stopPolling()
    }
  }, [jobId])

  return state
}
