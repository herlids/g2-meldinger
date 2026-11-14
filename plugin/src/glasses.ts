// Brille-UI: innboks → samtale (svarliste) → bekreft → sendt.
// Ring og brillestenger gir samme bevegelser: sveip opp/ned blar, trykk velger,
// dobbelttrykk går tilbake (i innboksen: avslutt-dialog).

import {
  CreateStartUpPageContainer,
  EvenAppBridge,
  ListContainerProperty,
  ListItemContainerProperty,
  OsEventTypeList,
  RebuildPageContainer,
  TextContainerProperty,
  TextContainerUpgrade,
  type EvenHubEvent,
} from '@evenrealities/even_hub_sdk'
import type { BridgeClient, BridgeState, Conversation } from './api'

const W = 576
const H = 288
const HEADER_H = 36
const CONV_TEXT_H = 120
const MAX_ITEM_BYTES = 63 // fastvaren godtar maks 63 byte UTF-8 per listeelement
const MAX_ITEMS = 20

type View =
  | { kind: 'inbox' }
  | { kind: 'conv'; id: string; openedAt: number; items: ConvItem[] }
  | { kind: 'confirm'; id: string; text: string }
  | { kind: 'read'; id: string; page: number; pages: string[] }
  | { kind: 'status'; text: string }

type ConvItem =
  | { type: 'reply'; text: string; ai?: boolean }
  | { type: 'ai-wait' }
  | { type: 'read' }
  | { type: 'dismiss' }
  | { type: 'back' }

export type Connection = 'ok' | 'offline' | 'unpaired'

export class GlassesUI {
  private view: View = { kind: 'inbox' }
  private state: BridgeState = { rev: -1, conversations: [], quickReplies: [] }
  private conn: Connection = 'offline'
  private renderedInboxKey = ''
  private queue: Promise<unknown> = Promise.resolve()
  private statusTimer: ReturnType<typeof setTimeout> | undefined
  private lastText = new Map<string, string>()

  private bridge: EvenAppBridge
  private api: BridgeClient
  constructor(bridge: EvenAppBridge, api: BridgeClient) {
    this.bridge = bridge
    this.api = api
  }

  // ---------- Offentlig ----------

  snapshot() {
    // Bare lettvekts navigasjon; meldingene ligger i hjelpeappen.
    const v = this.view
    if (v.kind === 'conv' || v.kind === 'read') return { view: 'conv', id: v.id }
    return { view: 'inbox' }
  }

  restore(s: { view?: string; id?: string } | undefined) {
    if (s?.view === 'conv' && s.id) this.view = { kind: 'conv', id: s.id, openedAt: 0, items: [] }
  }

  async start() {
    await this.serial(() =>
      this.bridge.createStartUpPageContainer(new CreateStartUpPageContainer({ ...this.inboxPage(true) })),
    )
    this.renderedInboxKey = ''
    await this.render()
  }

  async update(state: BridgeState | null, conn: Connection) {
    const connChanged = conn !== this.conn
    this.conn = conn
    if (state) this.state = state
    const v = this.view

    if (v.kind === 'inbox') {
      const key = this.inboxKey()
      if (key !== this.renderedInboxKey) await this.render()
      else if (connChanged) await this.upgradeHeader(this.inboxHeader())
      return
    }

    if (v.kind === 'conv') {
      const c = this.find(v.id)
      if (!c) return
      // AI-forslag kom rett etter åpning: tegn listen på nytt mens brukeren ennå ikke har blablet.
      const hadWait = v.items.some((i) => i.type === 'ai-wait')
      if (hadWait && c.aiStatus !== 'pending' && Date.now() - v.openedAt < 3000) {
        await this.openConv(v.id)
        return
      }
      await this.upgradeHeader(this.convText(c), 1, 'conv-text')
    }
  }

  async onEvent(e: EvenHubEvent) {
    const v = this.view

    if (e.sysEvent) {
      const t = e.sysEvent.eventType ?? 0
      if (t === OsEventTypeList.FOREGROUND_ENTER_EVENT) {
        this.renderedInboxKey = ''
        return this.render()
      }
      if (t === OsEventTypeList.DOUBLE_CLICK_EVENT) return this.back()
      if (t === OsEventTypeList.CLICK_EVENT && v.kind === 'read') return this.back()
      if (t === OsEventTypeList.CLICK_EVENT && v.kind === 'status') return this.goInbox()
      return
    }

    if (e.textEvent && v.kind === 'read') {
      const t = e.textEvent.eventType ?? 0
      const page = t === OsEventTypeList.SCROLL_BOTTOM_EVENT ? v.page + 1 : v.page - 1
      if (page >= 0 && page < v.pages.length) {
        v.page = page
        await this.upgradeHeader(this.readText(v), 1, 'read')
      }
      return
    }

    if (e.listEvent) {
      const idx = e.listEvent.currentSelectItemIndex ?? 0
      if (v.kind === 'inbox') {
        const c = this.state.conversations[idx]
        if (c) await this.openConv(c.id)
        return
      }
      if (v.kind === 'conv') return this.onConvItem(v, idx)
      if (v.kind === 'confirm') {
        if (idx === 0) return this.send(v.id, v.text)
        return this.openConv(v.id)
      }
    }
  }

  // ---------- Navigasjon ----------

  private async back() {
    const v = this.view
    if (v.kind === 'inbox') {
      // Systemets avslutt-dialog; opprydding skjer i SYSTEM_EXIT_EVENT.
      await this.serial(() => this.bridge.shutDownPageContainer(1))
      return
    }
    if (v.kind === 'confirm' || v.kind === 'read') return this.openConv(v.id)
    return this.goInbox()
  }

  private async goInbox() {
    clearTimeout(this.statusTimer)
    this.view = { kind: 'inbox' }
    this.renderedInboxKey = ''
    await this.render()
  }

  private async openConv(id: string) {
    const c = this.find(id)
    if (!c) return this.goInbox()
    const items: ConvItem[] = []
    if (c.canReply) {
      for (const s of c.ai) items.push({ type: 'reply', text: s, ai: true })
      if (c.aiStatus === 'pending') items.push({ type: 'ai-wait' })
      for (const q of this.state.quickReplies) {
        if (!c.ai.includes(q)) items.push({ type: 'reply', text: q })
      }
    }
    items.push({ type: 'read' }, { type: 'dismiss' }, { type: 'back' })
    this.view = { kind: 'conv', id, openedAt: Date.now(), items: items.slice(-MAX_ITEMS) }
    await this.render()
  }

  private async onConvItem(v: Extract<View, { kind: 'conv' }>, idx: number) {
    const item = v.items[idx]
    if (!item) return
    switch (item.type) {
      case 'reply':
        this.view = { kind: 'confirm', id: v.id, text: item.text }
        return this.render()
      case 'ai-wait':
        await this.api.refreshAi(v.id)
        return this.openConv(v.id)
      case 'read': {
        const c = this.find(v.id)
        if (!c) return
        this.view = { kind: 'read', id: v.id, page: 0, pages: paginate(fullThread(c), 380) }
        return this.render()
      }
      case 'dismiss':
        await this.api.dismiss(v.id)
        this.state.conversations = this.state.conversations.filter((c) => c.id !== v.id)
        return this.goInbox()
      case 'back':
        return this.goInbox()
    }
  }

  private async send(id: string, text: string) {
    const c = this.find(id)
    await this.showStatus(`Sender til ${c?.title ?? 'mottaker'} …`, false)
    const res = await this.api.reply(id, text)
    if (res.ok) {
      this.state.conversations = this.state.conversations.filter((x) => x.id !== id)
      await this.showStatus(`● Sendt\n\n«${text}»`, true)
    } else {
      await this.showStatus(`○ Ikke sendt\n${res.error}\n\nTrykk for å gå tilbake`, false)
    }
  }

  private async showStatus(text: string, autoReturn: boolean) {
    clearTimeout(this.statusTimer)
    this.view = { kind: 'status', text }
    await this.render()
    if (autoReturn) this.statusTimer = setTimeout(() => void this.goInbox(), 1800)
  }

  // ---------- Tegning ----------

  private async render() {
    const v = this.view
    let page: RebuildPageContainer
    switch (v.kind) {
      case 'inbox':
        page = this.inboxPage(false)
        this.renderedInboxKey = this.inboxKey()
        break
      case 'conv': {
        const c = this.find(v.id)
        if (!c) return this.goInbox()
        if (!v.items.length) return this.openConv(v.id) // etter gjenoppretting
        page = this.twoPart('conv-text', this.convText(c), v.items.map((i) => this.itemLabel(i)))
        break
      }
      case 'confirm': {
        const c = this.find(v.id)
        page = this.twoPart(
          'confirm-text',
          `Send til ${c?.title ?? '?'} (${c?.appLabel ?? ''})?\n\n«${v.text}»`,
          ['● Send', '○ Avbryt'],
        )
        break
      }
      case 'read':
        page = this.fullText('read', this.readText(v))
        break
      case 'status':
        page = this.fullText('status', v.text)
        break
    }
    this.lastText.clear()
    await this.serial(() => this.bridge.rebuildPageContainer(page))
  }

  private inboxPage(startup: boolean) {
    const convs = this.state.conversations.slice(0, MAX_ITEMS)
    const names = convs.length
      ? convs.map((c) => clip(`${c.appLabel} · ${c.title}: ${lastText(c)}`))
      : [this.conn === 'ok' ? 'Ingen nye meldinger' : '—']
    const header = startup ? 'Meldinger' : this.inboxHeader()
    return new RebuildPageContainer({
      containerTotalNum: 2,
      textObject: [text(1, 'header', 0, 0, W, HEADER_H, header, 0)],
      listObject: [list(2, 'inbox', 0, HEADER_H, W, H - HEADER_H, names)],
    })
  }

  private inboxHeader() {
    const n = this.state.conversations.length
    if (this.conn === 'unpaired') return 'Meldinger · Åpne appen på telefonen og skriv inn koden'
    if (this.conn === 'offline') return 'Meldinger · Finner ikke hjelpeappen …'
    return `Meldinger · ${n === 0 ? 'ingen nye' : n === 1 ? '1 samtale' : `${n} samtaler`}`
  }

  private inboxKey() {
    return (
      this.conn +
      '|' +
      this.state.conversations
        .slice(0, MAX_ITEMS)
        .map((c) => `${c.id}:${c.updatedAt}`)
        .join(',')
    )
  }

  private twoPart(name: string, body: string, items: string[]) {
    return new RebuildPageContainer({
      containerTotalNum: 2,
      textObject: [text(1, name, 0, 0, W, CONV_TEXT_H, body, 0)],
      listObject: [list(2, 'choices', 0, CONV_TEXT_H, W, H - CONV_TEXT_H, items)],
    })
  }

  private fullText(name: string, body: string) {
    return new RebuildPageContainer({
      containerTotalNum: 1,
      textObject: [text(1, name, 0, 0, W, H, body, 1)],
    })
  }

  private convText(c: Conversation) {
    const head = `${c.title} · ${c.appLabel}`
    const recent = c.messages.slice(-3)
    const lines = recent.map((m) => (m.sender && m.sender !== c.title ? `${m.sender}: ${m.text}` : m.text))
    let body = lines.join('\n')
    if (body.length > 170) body = '…' + body.slice(-169)
    const note = c.canReply ? '' : '\n(Appen tillater ikke svar fra varsel)'
    return `${head}\n${body}${note}`
  }

  private readText(v: Extract<View, { kind: 'read' }>) {
    const nav = v.pages.length > 1 ? `\n\n[${v.page + 1}/${v.pages.length}] sveip for å bla · trykk = tilbake` : '\n\nTrykk = tilbake'
    return v.pages[v.page] + nav
  }

  private itemLabel(i: ConvItem): string {
    switch (i.type) {
      case 'reply':
        return clip(i.ai ? `★ ${i.text}` : i.text)
      case 'ai-wait':
        return '☆ AI-forslag lages … trykk for å hente'
      case 'read':
        return '▶ Les hele samtalen'
      case 'dismiss':
        return '■ Fjern fra innboksen'
      case 'back':
        return '◀ Tilbake'
    }
  }

  private async upgradeHeader(content: string, id = 1, name = 'header') {
    if (this.lastText.get(name) === content) return
    this.lastText.set(name, content)
    await this.serial(() =>
      this.bridge.textContainerUpgrade(
        new TextContainerUpgrade({ containerID: id, containerName: name, contentOffset: 0, contentLength: 0, content }),
      ),
    )
  }

  private find(id: string) {
    return this.state.conversations.find((c) => c.id === id)
  }

  // Alle bro-kall går i kø med tidsavbrudd (BLE tåler ikke samtidige kall).
  private serial<T>(fn: () => Promise<T>): Promise<T | undefined> {
    const run = this.queue.then(() =>
      Promise.race([fn(), new Promise<undefined>((r) => setTimeout(() => r(undefined), 5000))]),
    )
    this.queue = run.catch(() => undefined)
    return run.catch((err) => {
      console.error('bridge-kall feilet', err)
      return undefined
    })
  }
}

// ---------- Hjelpere ----------

function text(id: number, name: string, x: number, y: number, w: number, h: number, content: string, capture: 0 | 1) {
  return new TextContainerProperty({
    xPosition: x, yPosition: y, width: w, height: h,
    borderWidth: 0, borderColor: 0, paddingLength: 4,
    containerID: id, containerName: name,
    content: content.slice(0, 1000) || ' ',
    isEventCapture: capture,
  })
}

const ITEM_H = 42 // målt i simulatoren

function list(id: number, name: string, x: number, y: number, w: number, maxH: number, items: string[]) {
  const names = items.slice(0, MAX_ITEMS).map((s) => clip(s))
  // Fastvaren midtstiller elementene vertikalt; krymp høyden så listen starter rett under teksten.
  const h = Math.min(maxH, names.length * ITEM_H + 6)
  return new ListContainerProperty({
    xPosition: x, yPosition: y, width: w, height: h,
    borderWidth: 0, borderColor: 0, paddingLength: 2,
    containerID: id, containerName: name,
    isEventCapture: 1,
    itemContainer: new ListItemContainerProperty({
      itemCount: names.length,
      itemWidth: 0,
      isItemSelectBorderEn: 1,
      itemName: names,
    }),
  })
}

function lastText(c: Conversation) {
  return c.messages[c.messages.length - 1]?.text ?? ''
}

function fullThread(c: Conversation) {
  return `${c.title} · ${c.appLabel}\n\n` + c.messages.map((m) => `${m.sender || c.title}: ${m.text}`).join('\n')
}

const enc = new TextEncoder()
const bytes = (s: string) => enc.encode(s).length

// Kutter til maks antall byte uten å dele et tegn (eller emoji) på midten.
export function clip(s: string, max = MAX_ITEM_BYTES) {
  const one = s.replace(/\s+/g, ' ').trim()
  if (bytes(one) <= max) return one
  const chars = Array.from(one)
  while (chars.length && bytes(chars.join('') + '…') > max) chars.pop()
  return chars.join('').trimEnd() + '…'
}

export function paginate(s: string, size: number): string[] {
  const pages: string[] = []
  let rest = s
  while (rest.length > size) {
    let cut = rest.lastIndexOf(' ', size)
    if (cut < size * 0.6) cut = size
    pages.push(rest.slice(0, cut))
    rest = rest.slice(cut).trimStart()
  }
  pages.push(rest)
  return pages
}
