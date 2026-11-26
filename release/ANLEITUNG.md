# SkyTrade – Installation (fertig zum Benutzen)

Im Ordner `mods/` liegen beide Dateien, die du brauchst:

| Datei | Was ist das? |
| --- | --- |
| `skytrade-1.0.0.jar` | die Mod selbst (SQLite ist schon eingebaut) |
| `fabric-api-0.141.6+1.21.11.jar` | Fabric API, wird von der Mod benötigt |

## 1. Voraussetzungen
- Minecraft **Java Edition 1.21.11**
- **Java 21** (der offizielle Minecraft Launcher bringt das automatisch mit)

## 2. Fabric Loader installieren (einmalig)
1. https://fabricmc.net/use/installer/ öffnen und den Installer herunterladen.
2. Starten → Reiter **Client** → Minecraft-Version **1.21.11** → Loader **0.17 oder neuer** → **Installieren**.
3. Im Minecraft Launcher gibt es jetzt das Profil **fabric-loader-1.21.11**.

## 3. Mods reinkopieren
1. Den Mods-Ordner öffnen:
   - Windows: `Win + R` → `%appdata%\.minecraft` → Ordner `mods` (falls nicht da: anlegen)
   - macOS: `~/Library/Application Support/minecraft/mods`
   - Linux: `~/.minecraft/mods`
2. **Beide** `.jar`-Dateien aus `release/mods/` dort hineinkopieren.

## 4. Starten
Launcher → Profil **fabric-loader-1.21.11** → Spielen. Fertig.

## 5. Benutzen
| Befehl / Taste | Funktion |
| --- | --- |
| **K** | Terminal (Dashboard) öffnen |
| `/skytrade` | dasselbe per Befehl |
| `/skytrade ah` | Auction-House-Flips |
| `/skytrade bazaar` | Bazaar-Flips |
| `/skytrade deals` | Coflnet-Deals (live gegengeprüft) |
| `/skytrade status` | API-Status, Datenalter |
| `/skytrade help` | alle Befehle |

Im Chat erscheinen Flip-Meldungen mit **[VIEW]**. Klick darauf schreibt z. B. `/viewauction …`
in deine Chatzeile – du drückst selbst Enter und kaufst selbst. Die Mod klickt, kauft oder
sendet **nie** etwas an Hypixel.

Einstellungen und Datenbank liegen in `.minecraft/config/skytrade/`.
