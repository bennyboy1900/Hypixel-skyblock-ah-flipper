# SkyTrade – Benchmark- und Testbericht

Stand: 26.11.2026 · Datenbasis: echte Hypixel-Marktdaten vom 26.11.2026, 09:27–11:25 UTC, plus 30 Tage Bazaar-Verlauf (Coflnet) für 11 Items.

## Kurzfassung

| Bereich | Ergebnis | Einschätzung |
| --- | --- | --- |
| AH-Preisschätzung (2.241 echte Verkäufe, walk-forward) | Medianfehler **2,6 %**, **86,5 %** innerhalb ±20 % | gut bei exakten Vergleichen, schwach bei „ähnlichen“ |
| AH-Flips (Simulation auf echten Verkäufen) | 68 Käufe, **+37,1 Mio.** realisiert (Prognose +38,4 Mio.), **94 % Gewinnquote** | profitabel, Gewinn stark auf wenige Snipes konzentriert |
| AH live (65 min, nur beobachtet) | 63 Flips gefunden, **60 % davon in derselben Stunde von anderen gekauft** | die Angebote waren echt unterbewertet; Konkurrenz ist schnell |
| Bazaar (Replay auf echten Orderbüchern, minütlich) | 68 abgeschlossene Flips, **+67,7 Mio.** auf 426 Mio. eingesetztes Kapital, **0 Verluste**, 83 % der Prognose realisiert | profitabel; Füllmenge ist modelliert, Preise und Unterbieten sind echt |
| Bazaar 30 Tage (alter Backtester) | „Balanced“ +90,8 % in 29 Tagen | **nicht glaubwürdig**, zu optimistisch, siehe unten |
| Robustheit | 9 Stresstests, ~185.000 zufällige und kaputte Eingaben | nach den Fixes keine Abstürze, keine NaN- oder negativen Preise |
| Gefundene und behobene Fehler | **14** | siehe Abschnitt „Behobene Fehler“ |

**Fazit:** Bazaar- und AH-Flips waren in diesen Daten profitabel, und die Preisschätzung ist bei Items mit genug Verkäufen genau. Die Datenbasis umfasst aber nur knapp 2 Stunden (Bazaar zusätzlich 30 Tage für 11 Items). Das ist ein Belastungstest, kein Beweis für dauerhaften Gewinn. Die echten Grenzen stehen unten.

---

## 1. Wie getestet wurde

Alle Tests sind **walk-forward**: Eine Entscheidung zum Zeitpunkt t sieht nur Daten von vor t. Es gibt keinen Blick in die Zukunft, außer an zwei klar markierten Stellen in der Auswertung des Ergebnisses.

### Datenquellen

| Datensatz | Quelle | Umfang |
| --- | --- | --- |
| Verkäufe mit komplettem Item (NBT) | Hypixel `auctions_ended`, jede Minute aufgezeichnet | **7.196 Verkäufe**, 1.335 verschiedene Items, 1,8 h |
| Bazaar-Orderbücher (Top 5 je Seite) | Hypixel `bazaar`, jede Minute | **120 Schnappschüsse**, 1,8 h |
| Vollständiges Orderbuch | Hypixel `bazaar` | 1 Schnappschuss (Messung der Konkurrenz) |
| Bazaar-Verlauf 30 Tage | Coflnet (2 h bzw. täglich) | 11 Items, 472 Punkte |
| Live-Lauf des Mods | kompletter Mod headless gegen die echte API | 65 min, 4.045 Verkäufe beobachtet |

Coflnet sperrt die IP dieses Cloud-Servers zeitweise, weil sich viele Nutzer sie teilen. Darum stammt fast alles aus der Hypixel-API, und Coflnet wurde nur sparsam genutzt. Ein erster Sammler wiederholte abgelehnte Anfragen schnell. Er wurde gestoppt, und der jetzige Sammler respektiert jede Ablehnung: kein erneuter Versuch, 2 Minuten Pause.

### Die Tests

1. **AH-Preisschätzung:** Jeder echte BIN-Verkauf wird mit der Preisschätzung des Mods bewertet, und zwar nur mit den Verkäufen davor. Danach wird mit dem echten Preis verglichen. Sechs Varianten laufen auf denselben Daten (A/B-Vergleich).
2. **AH-Flip-Simulation:** Jeder echte BIN-Verkauf zum Preis P war ein Angebot, das der Bot hätte kaufen können. Der Bot kauft, wenn seine Regeln (Mindestgewinn 50k, ROI ≥ 2 %, Konfidenz ≥ 45 %, Rauschregel) es erlauben. Das Ergebnis kommt aus den echten späteren Verkäufen identischer Items:
   - Ein späterer Verkauf liegt ≥ dem Wiederverkaufspreis des Bots: verkauft zu diesem Preis.
   - Spätere Verkäufe liegen nur darunter: Der Bot geht mit dem Preis auf deren Median runter.
   - Bis Datenende kein identischer Verkauf: „unverkauft“, wird getrennt ausgewiesen.
   - Konkurrenzangebote: Identische Items, die in den 15 Minuten danach verkauft wurden, waren beim Kauf schon gelistet. Sie begrenzen den Wiederverkaufspreis so, wie es der Mod live mit dem günstigsten BIN macht. Das ist die eine markierte Stelle, an der späteres Wissen verwendet wird; sie kann den Gewinn nur senken.
3. **Bazaar-Replay:** Alle 10 Minuten läuft der echte Bazaar-Analyzer des Mods auf dem aufgezeichneten Buch, mit Verlauf nur aus früheren Schnappschüssen. Seine Top-Flips werden dann Minute für Minute durch die echten Bücher verfolgt:
   - Die Kauforder wird nur gefüllt, solange sie die höchste ist. Wird sie überboten, setzt der Bot neu auf oberste Order + 0,1, falls der Flip noch profitabel ist, sonst bricht er ab.
   - Verkaufsangebote werden beim Unterbieten genauso angepasst.
   - Was am Ende noch im Lager liegt, wird sofort an die höchste Kauforder verkauft (nach Steuer).
   - Füllmenge = Wochenvolumen/168 pro Stunde × Anteil bei Konkurrenz. Die Konkurrenz zählt mindestens 14 Orders, der gemessene Median.
4. **30-Tage-Backtest:** der eingebaute Backtester auf dem Coflnet-Verlauf (3 Presets).
5. **Live-Forward-Test:** Der komplette Mod lief 65 Minuten gegen die echte API. Jeder gefundene Flip wurde notiert und danach mit den echten Verkäufen abgeglichen.
6. **Stresstests:** ~185.000 zufällige, kaputte und extreme Eingaben (NaN, unendlich, negativ, 1e308, abgeschnittenes oder verfälschtes NBT, falsche JSON-Typen) für jeden Parser und jede Preisrechnung.

---

## 2. Ergebnisse im Detail

### 2.1 AH-Preisschätzung (2.241 bewertete Verkäufe)

Abdeckung: 31,9 % aller BIN-Verkäufe konnten bewertet werden. Für den Rest gab es im Datenfenster noch keine 5 vergleichbaren Verkäufe. Das ist der Kaltstart: In der echten Nutzung sammelt der Mod über Stunden und Tage mehr Verkäufe und nutzt zusätzlich Coflnet.

| Gruppe | Anzahl | Medianfehler | ±10 % | ±20 % | >20 % zu hoch |
| --- | --- | --- | --- | --- | --- |
| **Alle** | 2.241 | **2,6 %** | 74,3 % | 86,5 % | 5,1 % |
| Exakte Vergleiche | 2.195 | 2,5 % | – | – | – |
| Ähnliche (Upgrades herausgerechnet) | 46 | 25,9 % | 39,1 % | 41,3 % | 21,7 % |
| unter 1 Mio. | 957 | 4,9 % | 61,4 % | 79,4 % | 8,5 % |
| 1–10 Mio. | 1.055 | 2,0 % | 83,0 % | 91,5 % | 2,9 % |
| 10–100 Mio. | 217 | 1,1 % | 88,0 % | 93,5 % | 0,9 % |
| über 100 Mio. | 12 | 2,3 % | 91,7 % | 91,7 % | 0,0 % |
| Konfidenz ≥ 70 | 1.284 | 2,0 % | 82,0 % | 92,4 % | 3,5 % |
| Konfidenz 45–70 | 726 | 3,6 % | 68,6 % | 83,9 % | 5,9 % |
| Konfidenz < 45 | 231 | 10,0 % | 49,8 % | 62,3 % | 11,3 % |

- **Die Konfidenz ist gut kalibriert:** höhere Konfidenz heißt klar kleinerer Fehler. Unter 45 % zeigt der Mod keine Flips mehr an.
- **Die größten „Fehler“ sind keine Fehler, sondern echte Snipes.** Eine Zombie Commander Whip wurde für 65 Coins verkauft (Schätzung 65k), ein Ice Spray Wand für 10k (Schätzung 6,65 Mio.). Da hat jemand viel zu billig gelistet. Der Wert ist der Durchschnitt der relativen Fehler und damit stark verzerrt (94 %), deshalb zeigt die Tabelle nur Medianwerte.
- **„Ähnliche“ Bewertungen sind unzuverlässig:** 26 % Medianfehler, 22 % deutlich zu hoch. Deren Konfidenz wurde darum gesenkt (Fix 11), sodass sie normalerweise nicht als Flip erscheinen.

**A/B-Vergleich der Varianten:**

| Variante | bewertet | Medianfehler | ±20 % | >20 % zu hoch |
| --- | --- | --- | --- | --- |
| **Standard (55 %, Enchants ab 5k, Paar-Limit 3)** | 2.241 | 2,6 % | 86,5 % | 5,1 % |
| Enchants erst ab 150k (alt) | 2.241 | 2,6 % | 86,5 % | 5,1 % |
| Upgrades ignoriert | 2.233 | 2,5 % | 86,7 % | 4,9 % |
| Upgrades fast voll angerechnet | 2.242 | 2,6 % | 86,5 % | 5,1 % |
| strenger Wash-Filter (Paar nur 1×) | 2.123 | 2,7 % | 86,2 % | 5,4 % |
| ohne Wash-Filter | 2.263 | 2,6 % | 86,2 % | 5,0 % |

- **Upgrade-Bewertung und Enchant-Schwelle machen in diesem Datensatz fast keinen messbaren Unterschied.** 98 % der Bewertungen nutzen exakte Vergleiche, die dieselben Upgrades haben, sodass sich die Upgrade-Werte rausrechnen. Wichtig wird die Upgrade-Logik erst bei seltenen, stark aufgewerteten Items. Davon gab es in 2 Stunden nur 46 Fälle, zu wenige für eine sichere Aussage. Das ist ehrlich gesagt eine offene Frage, siehe Abschnitt 4.
- **Der Wash-Filter kostet jetzt keine Genauigkeit mehr** (2,6 % wie ohne Filter) und schützt trotzdem vor Pumps. Vor Fix 9 kostete er Abdeckung und Genauigkeit: nur 26 % statt 32 % der Verkäufe konnten bewertet werden, und der Medianfehler lag bei 3,8 % statt 2,6 %.

### 2.2 AH-Flip-Simulation

| | mit Rauschregel (Standard) | ohne Rauschregel |
| --- | --- | --- |
| Käufe | 68 | 81 |
| Kapital eingesetzt | 290,9 Mio. | 395,2 Mio. |
| verkauft zum Zielpreis | 44 | 52 |
| verkauft unter Zielpreis | 6 | 7 |
| bis Datenende unverkauft | 18 (60,8 Mio. gebunden) | 22 (**147,7 Mio.** gebunden) |
| **realisierter Gewinn** | **+37,1 Mio.** | +37,3 Mio. |
| vorhergesagter Gewinn (abgeschlossene) | +38,4 Mio. | +40,9 Mio. |
| ROI auf abgeschlossene | 16,1 % | 15,1 % |
| Gewinnquote | **94,0 %** | 93,2 % |
| größter Verlust | −70k | −691k |
| Median-Zeit bis Verkauf | 5 min | 4 min |

- **Die Prognose stimmt:** Realisiert wurden 97 % des vorhergesagten Gewinns.
- **Die Rauschregel bleibt an:** gleicher Gewinn, aber weniger als die Hälfte an gebundenem Kapital, und der größte Verlust ist 10× kleiner.
- **Der Gewinn ist konzentriert:** Die 5 besten Flips (Scatha-Mecha-Skin +7,7 Mio., Ice Spray Wand +6,5 Mio., Eagle-Plush-Skin +3,4 Mio., Scatha-Pet +3,0 Mio., Shriveled Wasp +1,6 Mio.) bringen etwa 22 der 37 Mio. Ohne diese seltenen Snipes bleiben viele kleine, solide Gewinne.
- **Wichtig:** Live hätte der Mod den Ice-Spray-Wand-Snipe (−99,8 %) nur mit bestätigtem 7-Tage-Verlauf gezeigt (MarketGuard). Die Simulation enthält den MarketGuard nicht, weil Coflnet auf diesem Server gesperrt war.
- Die Verluste sind klein und kommen vom Unterbieten: Pressure Artifact −70k, Bal-Pet −41k, Armadillo-Pet −8k.

### 2.3 AH live (Forward-Test, 65 Minuten)

- Der Mod hat 63 Flips gefunden (53 exakt, 10 ähnlich). Bei jedem Scan wurden alle rund 39.000 BIN-Angebote dekodiert, ohne einen Dekodierfehler.
- **38 der 63 Angebote (60 %) hat ein anderer Spieler noch in derselben Stunde gekauft.** Die Unterbewertung war also echt, aber die Konkurrenz ist schnell. Wer manuell spielt, schafft nur einen Teil davon.
- Bei 33 Flips gab es spätere identische Verkäufe. Ihr Median lag **1,9 % unter** dem vorhergesagten Wiederverkaufspreis. Das ist leicht optimistisch, wird aber von Mindest-ROI und Gebühren abgedeckt.
- Dieser Lauf hat zwei Fehler aufgedeckt: winzige Flips mit 844 Coins Gewinn und Ratespiele mit 9 % Konfidenz wurden angezeigt. Siehe Fixes 12 und 13.
- **Coflnet-Deals:** 300 Deals wurden geprüft. Darunter waren Bazaar-„Flips“ mit +3.850 % (tote Orderbücher, 1–4 Verkäufe pro Woche, 1.300 bis 22.000 Stunden Füllzeit) und ein Midas-Staff-Craft mit +136 %. Beides wird jetzt aussortiert (Fix 14). Von Coflnets 1.850 Bazaar-Flips sind nach Coflnets eigener Schätzung nur 19 in unter 12 Stunden füllbar.

### 2.4 Bazaar-Replay auf echten Büchern

| Kennzahl | Wert |
| --- | --- |
| Signale (Top-Flips des Analyzers) | 80 |
| davon mit Füllung | 73 |
| komplett gekauft und verkauft | 68 |
| Kapital eingesetzt | 425,6 Mio. |
| **realisierter Gewinn** | **+67,7 Mio.** (15,9 % auf das eingesetzte Kapital, je Flip über Minuten bis ~1,5 h) |
| Prognose für die gefüllte Menge | +81,7 Mio. → **83 % realisiert** |
| Verlust-Trades | **0** |
| Neusetzen nach Überbieten: Kauf / Verkauf | 83 / 253 |
| Füllquote (gekauft / geplant) | 41 % |
| beste Items | Perfect Jade Gem +10,3 Mio., Shard Murderwing +10,1 Mio., Blue Ring +8,4 Mio., Deep Sea Orb +8,1 Mio. |

Was davon real und was modelliert ist:
- **Real:** Preise, Spreads, Kursbewegungen und jedes Überbieten und Unterbieten (253× musste das Verkaufsangebot nachgezogen werden).
- **Modelliert:** wie viel von den Verkäufen *unsere* Order abbekommt. Das kommt aus dem Wochenvolumen und mindestens 14 Konkurrenten, dem am vollen Buch gemessenen Median.
- **Konsequenz:** Die Gewinnquote von 100 % ist wahrscheinlich etwas zu gut. Echte Spieler haben auch Verzögerung beim Neusetzen, und der Testzeitraum ist kurz. Die 41 % Füllquote zeigen aber, dass der Test nicht einfach alles füllt.

### 2.5 30-Tage-Backtest (eingebauter Backtester, 11 Items)

| Preset | Endwert | Trades | Gewinne | Max. Drawdown |
| --- | --- | --- | --- | --- |
| Conservative | +2,3 % | 4 | 4 | 0,1 % |
| Balanced | +90,8 % | 81 | 72 | 2,5 % |
| Aggressive | +25,6 % | 31 | 28 | 4,8 % |

**Diesem Ergebnis nicht trauen.**
- Es gibt nur Datenpunkte alle 2 Stunden bzw. täglich. Unterbieten und Kursschwankungen dazwischen sieht der Simulator nicht.
- Er füllt deshalb fast immer zum Wunschpreis. Beim ersten Lauf war der realisierte Gewinn sogar exakt gleich der Prognose.
- Ich habe die angenommene Konkurrenz von 5 auf die gemessenen 14 Orders korrigiert (Fix 6). Das senkte Aggressive von +112,8 % auf +25,6 %, aber Balanced ist mit +90,8 % weiter unrealistisch.

Für Gewinnerwartungen gilt das minütliche Replay aus Abschnitt 2.4.

---

## 3. Behobene Fehler

| # | Fehler | gefunden durch | Fix |
| --- | --- | --- | --- |
| 1 | Bazaar zeigte „Flips“ auf toten Büchern (z. B. 7.676.004 % Spread, 116 Tage Haltedauer) | Stresstest | abgelehnt, wenn 1 Stück nicht in 3× der max. Haltedauer kauf- und verkaufbar ist |
| 2 | MarketGuard akzeptierte Fair Value 0 oder NaN bei kaputtem Verlauf | Stresstest | Eingaben und Verlauf werden auf Plausibilität geprüft, Faktor < 0,2 wird abgelehnt |
| 3 | Upgrade-Wert negativ oder unendlich bei korrupten Mengen oder absurden Preisen | Stresstest | Mengen ≤ 0 werden ignoriert, Kosten-Überlauf wird erkannt |
| 4 | Überlauf bei Preisen nahe 1e308 in Bazaar, Upgrades und Verlauf | Stresstest | globale Obergrenze für plausible Coins (1 Billion) in allen Parsern und Rechnungen |
| 5 | Wash-Filter verwarf echte Nachfrage (max. 2 Verkäufe pro Verkäufer) | Backtest | entfernt, Massenverkäufer mit vielen Käufern zählen |
| 6 | Backtester nahm 5 statt der gemessenen 14 Konkurrenzorders an | Messung am echten Buch | 14 |
| 7 | Items mit gleicher ID, aber anderem Wert landeten in einem Topf: Kuchenjahr, *welches* Enchant auf einem Enchanted Book ist, Runen, Party-Hut, Shiny, Tränke | Backtest (New Year Cake 5,2–8 Mio.) | „Variante“ ist Teil der Item-Identität |
| 8 | Flips innerhalb des normalen Preisrauschens erzeugten gebundenes Kapital und Verluste | Backtest | Rauschregel: Netto-Marge ≥ Preisstreuung (einstellbar) |
| 9 | Selbst-Pump-Regel warf alle früheren Verkäufe des Verkäufers weg, also die besten Vergleiche bei Massenverkäufern | Backtest (Fehler 3,8 % statt 2,6 %) | ausgeschlossen werden nur Verkäufe, die der Verkäufer selbst *gekauft* hat |
| 10 | Wash-Paare je Richtung getrennt gezählt: zwei Accounts im Hin-und-her ergaben doppelt so viele Vergleiche | Code-Review beim Fix 9 | ein Paar zählt in beide Richtungen zusammen, max. 3 Verkäufe |
| 11 | „Ähnliche“ Bewertungen zu selbstsicher (22 % deutlich zu hoch) | Backtest | Konfidenz ×0,6 statt ×0,8 |
| 12 | AH zeigte Mini-Flips (844 Coins) | Live-Test | Mindestgewinn, Mindest-ROI und Mindestkonfidenz gelten auch für die Anzeige |
| 13 | AH zeigte Ratespiele mit 9 % Konfidenz und „+10 Mio.“ | Live-Test | wie 12 |
| 14 | Coflnet-Deals: tote Bazaar-Bücher mit +3.850 % und Midas-Crafts mit +136 % | Live-Test | Spread > 100 % und nicht füllbare Bücher fliegen raus; Crafts brauchen ≥ 3 Verkäufe und bei > 100 % ROI ≥ 10 |

Alle Fixes haben Tests: insgesamt 53 Tests, davon 5 optional (Live, Benchmark, Forward).

---

## 4. Grenzen dieses Berichts

- **Kurzer Zeitraum:** knapp 2 Stunden echte AH- und Bazaar-Daten an einem Tag. Es gibt keine Aussage über Wochenenden, Updates, Mayor-Wechsel oder Events. Für eine belastbare Gewinnaussage bräuchte es mehrere Tage Aufzeichnung.
- **Kaltstart:** Der Mod bewertete nur 32 % der Verkäufe, weil er erst seit Minuten Daten hatte. Mit Coflnet (auf deinem eigenen PC nicht gesperrt) und längerer Laufzeit wird es mehr.
- **MarketGuard und 7-Tage-Verlauf** konnten nicht getestet werden, weil Coflnet diesen Server gesperrt hatte. Ihre Logik ist durch Unit- und Stresstests abgesichert, aber nicht mit echten Ergebnissen gemessen.
- **Upgrade-Bewertung:** Ihr Nutzen ließ sich nicht messen (nur 46 passende Fälle). Ob 5k oder 150k als Enchant-Schwelle besser ist, ist mit diesen Daten nicht entscheidbar; beide Varianten waren gleich.
- **Bazaar-Füllmenge ist modelliert,** Preise und Unterbieten sind echt.
- **Schnelligkeit:** 60 % der guten AH-Angebote waren innerhalb einer Stunde weg. Manuell holst du nur einen Teil der simulierten Gewinne.
- **Die simulierten Gewinne gehen davon aus, dass du jedes Signal sofort nutzt.** Mit einem Konto und begrenztem Kapital ist der echte Gewinn kleiner.

## 5. Empfehlungen

1. **Mit Konfidenz ≥ 70 anfangen.** Dort liegen 92 % der Schätzungen innerhalb ±20 %.
2. **Items über 1 Mio. bevorzugen.** Unter 1 Mio. ist die Schätzung deutlich ungenauer (Medianfehler 4,9 % statt ≤ 2 %).
3. **Flips mit „(unconfirmed)“ vorsichtig behandeln** und vor dem Kauf das Item ansehen.
4. **Bazaar-Flips auf liquiden Items** (Gems, Shards, Ringe, Orbs) waren im Replay die sicherste Strategie.
5. **Den Mod auf deinem PC laufen lassen,** dann ist Coflnet nicht gesperrt und der 7-Tage-Schutz aktiv.
6. **Die Aufzeichnung über mehrere Tage wiederholen** (Befehle unten) und diesen Bericht neu erzeugen.

## 6. Selbst reproduzieren

```bash
python3 -I benchmark/record_hypixel.py benchmark/data 600
```

```bash
./gradlew test -Dskytrade.bench=true --tests dev.skytrade.BenchmarkTest
```

```bash
./gradlew test -Dskytrade.forward=true -Dskytrade.forwardMinutes=60 --tests dev.skytrade.ForwardTest
```

```bash
python3 -I benchmark/report.py build/bench build/forward/forward.json
```

Der erste Befehl zeichnet 10 Stunden echte Daten auf (2 Anfragen pro Minute an die öffentliche Hypixel-API). Der zweite rechnet alle Backtests, der dritte führt den Live-Forward-Test aus, der vierte gibt alle Kennzahlen als JSON aus.
