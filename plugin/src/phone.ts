// Telefonsiden i Even-appen: paring med hjelpeappen og status.
import type { BridgeState } from './api'
import type { Connection } from './glasses'

interface Opts {
  initialToken: string
  onToken: (token: string) => void | Promise<void>
}

export function renderPhone(root: HTMLElement, opts: Opts) {
  root.innerHTML = `
    <main class="wrap">
      <h1>G2 Meldinger</h1>
      <p class="dim">Les og svar på SMS, Messenger, Snapchat og Gmail med ringen.</p>

      <section class="card">
        <div class="row"><span class="label">STATUS</span><span id="status" class="pill">Kobler til …</span></div>
        <p id="hint" class="dim small"></p>
      </section>

      <section class="card">
        <span class="label">PARINGSKODE</span>
        <p class="dim small">Åpne hjelpeappen «G2 Meldinger» på telefonen og skriv inn koden som står der.</p>
        <form id="pair" class="pair">
          <input id="code" inputmode="numeric" autocomplete="off" maxlength="6" placeholder="6 sifre" />
          <button type="submit">Koble til</button>
        </form>
      </section>

      <section class="card">
        <span class="label">PÅ BRILLENE</span>
        <ul class="dim small help">
          <li>Sveip opp/ned på ringen for å bla</li>
          <li>Trykk for å åpne en samtale eller velge et svar</li>
          <li>Trykk «✓ Send» for å bekrefte</li>
          <li>Dobbelttrykk for å gå tilbake</li>
        </ul>
      </section>

      <section class="card">
        <span class="label">I INNBOKSEN</span>
        <ul id="convs" class="convs small"></ul>
      </section>
    </main>`

  const $ = <T extends HTMLElement>(id: string) => root.querySelector<T>('#' + id)!
  const code = $<HTMLInputElement>('code')
  code.value = opts.initialToken

  $('pair').addEventListener('submit', (e) => {
    e.preventDefault()
    const v = code.value.replace(/\D/g, '')
    if (v.length === 6) void opts.onToken(v)
  })

  return {
    setStatus(conn: Connection, state: BridgeState | null) {
      const s = $('status')
      const hint = $('hint')
      s.className = 'pill ' + conn
      if (conn === 'ok') {
        s.textContent = 'Tilkoblet'
        hint.textContent = ''
      } else if (conn === 'unpaired') {
        s.textContent = 'Ikke paret'
        hint.textContent = 'Skriv inn koden fra hjelpeappen under.'
      } else {
        s.textContent = 'Finner ikke hjelpeappen'
        hint.textContent = 'Sjekk at «G2 Meldinger» er installert og har varseltilgang.'
      }
      const ul = $('convs')
      if (!state) return
      ul.innerHTML = state.conversations.length
        ? state.conversations
            .map((c) => `<li><b>${esc(c.title)}</b> <span class="dim">· ${esc(c.appLabel)}</span><br>${esc(c.messages.at(-1)?.text ?? '')}</li>`)
            .join('')
        : '<li class="dim">Ingen nye meldinger</li>'
    },
  }
}

function esc(s: string) {
  return s.replace(/[&<>"]/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[ch]!)
}
