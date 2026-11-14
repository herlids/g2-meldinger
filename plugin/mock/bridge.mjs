// Falsk Android-bro for testing i simulatoren. Samme API som hjelpeappen.
// Kjør: node mock/bridge.mjs   (kode: 123456)
import http from 'node:http'

const TOKEN = '123456'
let rev = 1
const now = Date.now()
const quickReplies = ['Ok 👍', 'Ringer deg snart', 'Kan ikke snakke nå', 'På vei!', 'Takk!']
let conversations = [
  {
    id: 'sms:kari', app: 'sms', appLabel: 'SMS', title: 'Kari',
    messages: [
      { sender: 'Kari', text: 'Hei! Er du hjemme i kveld?', ts: now - 60000 },
      { sender: 'Kari', text: 'Tenkte å ta med Multe på tur rundt Sofienbergparken', ts: now - 30000 },
    ],
    canReply: true, ai: [], aiStatus: 'pending', updatedAt: now - 30000,
  },
  {
    id: 'messenger:appetite', app: 'messenger', appLabel: 'Messenger', title: 'Appetite-prosjekt',
    messages: [{ sender: 'Marcus', text: 'Kan du sende over nye skisser før lunsj i morgen?', ts: now - 120000 }],
    canReply: true,
    ai: ['Ja, sender før lunsj', 'Trenger til ettermiddagen', 'Hvilket format vil du ha?'],
    aiStatus: 'ready', updatedAt: now - 120000,
  },
  {
    id: 'snapchat:ola', app: 'snapchat', appLabel: 'Snapchat', title: 'Ola',
    messages: [{ sender: 'Ola', text: 'sendte en snap', ts: now - 300000 }],
    canReply: false, ai: [], aiStatus: 'off', updatedAt: now - 300000,
  },
]
const sent = []

setTimeout(() => {
  const c = conversations.find((c) => c.id === 'sms:kari')
  if (c) { c.ai = ['Ja, jeg er hjemme!', 'Høres fint ut, når?', 'Ikke i kveld dessverre']; c.aiStatus = 'ready'; rev++ }
}, Number(process.env.AI_DELAY ?? 8000))

const cors = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Access-Control-Allow-Private-Network': 'true',
}

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x')
  if (req.method === 'OPTIONS') { res.writeHead(204, cors); return res.end() }
  const send = (code, body) => { res.writeHead(code, { ...cors, 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)) }
  if (url.searchParams.get('token') !== TOKEN) return send(401, { ok: false, error: 'unauthorized' })
  let body = ''
  for await (const ch of req) body += ch
  const data = body ? JSON.parse(body) : {}
  switch (url.pathname) {
    case '/api/state': return send(200, { rev, conversations, quickReplies })
    case '/api/reply': {
      const c = conversations.find((c) => c.id === data.id)
      if (!c) return send(404, { ok: false, error: 'Varselet er borte' })
      if (!c.canReply) return send(409, { ok: false, error: 'Appen støtter ikke svar' })
      sent.push({ to: c.title, text: data.text })
      console.log('SENDT', JSON.stringify({ to: c.title, text: data.text }))
      conversations = conversations.filter((x) => x.id !== data.id); rev++
      return send(200, { ok: true })
    }
    case '/api/dismiss': conversations = conversations.filter((x) => x.id !== data.id); rev++; return send(200, { ok: true })
    case '/api/ai': return send(200, { ok: true })
    case '/api/_sent': return send(200, sent)
    default: return send(404, { ok: false })
  }
}).listen(8765, '127.0.0.1', () => console.log('mock bridge på :8765'))
