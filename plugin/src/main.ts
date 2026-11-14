import { OsEventTypeList, waitForEvenAppBridge } from '@evenrealities/even_hub_sdk'
import { BridgeClient, BridgeError } from './api'
import { GlassesUI, type Connection } from './glasses'
import { renderPhone } from './phone'
import './style.css'

const POLL_MS = 2000
const TOKEN_KEY = 'g2m_token'
const SNAP_KEY = 'g2m_nav'

declare global {
  interface Window {
    __getStateSnapshot?: () => string
    __restoreState?: (snapshot: string) => void
  }
}

async function main() {
  const bridge = await waitForEvenAppBridge()
  // ?token=… i adressen brukes bare ved testing i simulatoren
  const devToken = import.meta.env.DEV ? new URLSearchParams(location.search).get('token') : null
  const token = devToken || (await bridge.getLocalStorage(TOKEN_KEY)) || ''
  const api = new BridgeClient(token)
  const ui = new GlassesUI(bridge, api)

  // Bakgrunnsflytting: Even-appen kopierer tilstanden over i en usynlig WebView.
  // SDK 0.0.16 eksporterer ikke hjelperne, så vi registrerer krokene selv.
  let pendingRestore: unknown
  window.__getStateSnapshot = () => JSON.stringify({ [SNAP_KEY]: ui.snapshot() })
  window.__restoreState = (snap: string) => {
    try {
      pendingRestore = JSON.parse(snap)?.[SNAP_KEY]
      ui.restore(pendingRestore as any)
    } catch {
      /* ignorer */
    }
  }

  let conn: Connection = token ? 'offline' : 'unpaired'
  const phone = renderPhone(document.getElementById('app')!, {
    initialToken: token,
    onToken: async (t) => {
      api.setToken(t)
      await bridge.setLocalStorage(TOKEN_KEY, t)
      void poll()
    },
  })

  await ui.start()

  bridge.onEvenHubEvent((e) => {
    const t = e.sysEvent?.eventType
    if (t === OsEventTypeList.SYSTEM_EXIT_EVENT || t === OsEventTypeList.ABNORMAL_EXIT_EVENT) {
      stopped = true
      return
    }
    if (t === OsEventTypeList.FOREGROUND_ENTER_EVENT) void poll()
    void ui.onEvent(e)
  })

  let stopped = false
  let busy = false
  async function poll() {
    if (busy || stopped) return
    busy = true
    try {
      const state = await api.state()
      conn = 'ok'
      phone.setStatus('ok', state)
      await ui.update(state, conn)
    } catch (err) {
      conn = err instanceof BridgeError && err.message === 'unauthorized' ? 'unpaired' : 'offline'
      phone.setStatus(conn, null)
      await ui.update(null, conn)
    } finally {
      busy = false
    }
  }

  void poll()
  setInterval(() => void poll(), POLL_MS)
}

main().catch((err) => console.error('Oppstart feilet', err))
