# G2 Meldinger

Les og svar på **SMS, Messenger, Snapchat og Gmail** fra Even Realities G2 med R1-ringen.
Hver melding får tre AI-forslag fra Claude (★) og dine egne standardsvar.

```
Varsel på telefonen ──► Hjelpeappen (Android) ──► 127.0.0.1:8765 ──► G2-appen (Even Hub) ──► brillene
                               ▲                                            │
                               └──── «Svar»-knappen i varselet ◄──── valgt svar ◄┘
```

Even Hub-SDK-et har ingen tilgang til meldinger, så løsningen har to deler:

| Del | Mappe | Hva den gjør |
|---|---|---|
| Hjelpeapp (Kotlin) | `android/` | Leser meldingsvarsler, lager AI-forslag, sender svar via varselets svarknapp, kjører en lokal server som bare kan nås fra telefonen |
| G2-app (TypeScript) | `plugin/` | Viser innboks og svarliste på brillene og tar imot trykk fra ringen |

## Bruk på brillene

| Ring / brillestang | Innboks | Samtale | Bekreft |
|---|---|---|---|
| Sveip opp/ned | Bla | Bla i svar | Velg Send/Avbryt |
| Trykk | Åpne samtale | Velg svar | Utfør |
| Dobbelttrykk | Avslutt-dialog | Tilbake | Tilbake |

Samtalelisten: ★ AI-forslag → standardsvar → ▶ Les hele samtalen → ■ Fjern fra innboksen → ◀ Tilbake.

**Hvilke samtaler vises?** Bare de som har et aktivt varsel, altså uleste. Leser du meldingen på telefonen, forsvinner den fra brillene. Svaret sendes gjennom varselets egen «Svar»-knapp, så appen kan ikke starte nye samtaler.

## Installasjon

### 1. Hjelpeappen (Obtainium)
1. GitHub Actions bygger en signert APK ved hver push til `main` og legger den ut som release.
2. I Obtainium: **Legg til app** → `https://github.com/herlids/g2-meldinger`. Repoet er offentlig, så du trenger ikke token.
3. Åpne **G2 Meldinger** og gjør følgende:
   - **Gi varseltilgang** (Innstillinger → Varseltilgang → G2 Meldinger)
   - **Slå av batterioptimalisering**, ellers kan Samsung stoppe appen i bakgrunnen
   - Lim inn en **Anthropic API-nøkkel** for AI-forslag (valgfritt)
   - Juster **standardsvarene**

### 2. G2-appen (Even Hub)
`.ehpk`-fila ligger i samme release. Den installeres via Even Hub som beta-bygg. Kjør dette på PC-en:
```bash
cd plugin
npx evenhub login                      # én gang, ber om passord
npx evenhub create ../g2-meldinger.ehpk --name "G2 Meldinger" --tagline "Svar på meldinger med ringen"
npx evenhub testers add <din-e-post>
npx evenhub promote 1.0.0 --to beta
```
Deretter: Even Realities-appen → **Meg → Beta tester** → Installer.
Til senere versjoner: øk `version` i `plugin/app.json` og kjør `npx evenhub upload ../g2-meldinger.ehpk -m "hva er nytt"`.

**Raskere under testing:** Kjør `npm run dev` i `plugin/` og `npx evenhub qr --url http://<pc-ip>:5173`, og skann koden i Even-appen. PC-en og telefonen må være på samme nett.

### 3. Paring
Åpne G2 Meldinger i Even-appen på telefonen og skriv inn den 6-sifrede koden fra hjelpeappen.
Test med **«Send testmelding til brillene»** i hjelpeappen. Den sender ikke noe til noen.

## Hvilke apper kan besvares?
Hjelpeappen viser under **Apper** om varslene fra hver app har svarknapp:
- **SMS (Google/Samsung Meldinger), Messenger:** ja
- **Snapchat, Gmail:** avhenger av appversjonen. Appen sjekker både vanlige svarknapper og Wear OS-svarknapper. Snaps (bilder og video) kan uansett ikke besvares.

## Personvern og sikkerhet
- Serveren lytter bare på `127.0.0.1`, så andre enheter på nettet når den ikke. Alle kall krever paringskoden, slik at nettsider på telefonen ikke kan lese meldingene.
- Med AI på sendes de siste meldingene i samtalen til Anthropic for å lage forslag. Slå det av i hjelpeappen om du ikke vil det.
- Signeringsnøkkelen ligger **kryptert** i `android/signing/` og dekrypteres i GitHub Actions med hemmeligheten `KEYSTORE_PASSWORD`. Repoet kan derfor være offentlig.

## Utvikling
```bash
cd plugin
npm install
node mock/bridge.mjs          # falsk hjelpeapp, kode 123456
npm run dev
npx evenhub-simulator "http://localhost:5173/?token=123456"
```
