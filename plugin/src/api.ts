// Klient mot Android-hjelpeappen «G2 Meldinger» som kjører en lokal server på telefonen.

export const BRIDGE_URL = 'http://127.0.0.1:8765'

export interface ChatLine {
  sender: string
  text: string
  ts: number
}

export interface Conversation {
  id: string            // stabil nøkkel fra Android (pakke + samtale)
  app: string           // 'sms' | 'messenger' | 'snapchat' | ...
  appLabel: string      // «SMS», «Messenger», «Snapchat»
  title: string         // avsender eller gruppenavn
  messages: ChatLine[]  // siste meldinger, eldst først
  canReply: boolean     // varselet har en svar-knapp
  ai: string[]          // AI-forslag (kan være tom)
  aiStatus: 'off' | 'pending' | 'ready' | 'error'
  updatedAt: number
}

export interface BridgeState {
  rev: number
  conversations: Conversation[]  // nyeste først
  quickReplies: string[]
}

export type ReplyResult = { ok: true } | { ok: false; error: string }

export class BridgeClient {
  private token: string
  constructor(token: string) {
    this.token = token
  }

  setToken(token: string) {
    this.token = token
  }

  private url(path: string) {
    return `${BRIDGE_URL}${path}${path.includes('?') ? '&' : '?'}token=${encodeURIComponent(this.token)}`
  }

  async state(): Promise<BridgeState> {
    const res = await fetchWithTimeout(this.url('/api/state'), { method: 'GET' }, 4000)
    if (res.status === 401) throw new BridgeError('unauthorized')
    if (!res.ok) throw new BridgeError(`http ${res.status}`)
    return res.json()
  }

  async reply(id: string, text: string): Promise<ReplyResult> {
    try {
      // text/plain holder forespørselen «enkel» (ingen CORS-preflight)
      const res = await fetchWithTimeout(
        this.url('/api/reply'),
        { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: JSON.stringify({ id, text }) },
        8000,
      )
      if (res.status === 401) return { ok: false, error: 'Feil paringskode' }
      const body = await res.json().catch(() => ({}))
      if (res.ok && body.ok) return { ok: true }
      return { ok: false, error: body.error || `HTTP ${res.status}` }
    } catch {
      return { ok: false, error: 'Fant ikke hjelpeappen' }
    }
  }

  async dismiss(id: string): Promise<void> {
    await fetchWithTimeout(
      this.url('/api/dismiss'),
      { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: JSON.stringify({ id }) },
      4000,
    ).catch(() => undefined)
  }

  async refreshAi(id: string): Promise<void> {
    await fetchWithTimeout(
      this.url('/api/ai'),
      { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: JSON.stringify({ id }) },
      4000,
    ).catch(() => undefined)
  }
}

export class BridgeError extends Error {}

async function fetchWithTimeout(url: string, init: RequestInit, ms: number): Promise<Response> {
  const ctrl = new AbortController()
  const t = setTimeout(() => ctrl.abort(), ms)
  try {
    return await fetch(url, { ...init, signal: ctrl.signal, cache: 'no-store' })
  } finally {
    clearTimeout(t)
  }
}
