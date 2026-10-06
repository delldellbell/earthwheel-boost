# Earthwheel Boost — comandă trepte viteză scooter (ESP32-C3 Super Mini + releu 5V + Android)

```
earthwheel-boost/
├── firmware/EarthwheelBoost/EarthwheelBoost.ino   ← cod placa ESP32-C3
├── android/                                       ← proiect aplicație Android (Kotlin)
└── .github/workflows/build-apk.yml                ← construiește APK-ul automat pe GitHub
```

## Cum funcționează
- **🐌 MOD MELC** = releu OPRIT (viteză normală). **🚀 MOD RACHETĂ** = releu CUPLAT.
- La **fiecare alimentare** placa pornește cu releul **OPRIT**. Starea nu se salvează, releul nu se cuplează niciodată singur.
- Releul se cuplează doar la comanda din aplicație (sau din telecomanda RF, dacă o adaugi) și rămâne cuplat cât timp placa e alimentată sau până îl oprești. Dacă telefonul se deconectează, releul **își păstrează** starea.
- Aplicația afișează starea **reală** a releului, confirmată de placă (notificări BLE + puls la 3 s).
- Conexiune prin **Bluetooth Low Energy**: nu ai nevoie de WiFi/internet, se conectează automat când deschizi aplicația.

## 1. Montaj

| ESP32-C3 Super Mini | Conectare |
|---|---|
| **5V** | +5V din convertorul DC-DC (step-down) al scooterului |
| **GND** | GND convertor + GND modul releu |
| **GPIO10** | IN modul releu (prin rezistor 10 kΩ **la GND** = pull-down) |
| GPIO3 (opțional) | DATA receptor RF 433 MHz (RXB6 / SRX882), alimentat la 3.3V |

Modul releu 5V: VCC → 5V, GND → GND, IN → GPIO10.
Contactele releului (**COM + NO**) se pun **în paralel cu firul/comutatorul de treaptă de viteză** al controllerului (exact unde ar fi comutatorul original de viteză). NO = deschis în modul melc, închis în modul rachetă.

**Recomandări pentru fiabilitate:**
- Folosește un modul releu cu jumper **H/L pus pe H** (high-level trigger) sau un releu driven de tranzistor NPN (BC547/2N2222 + diodă 1N4007 pe bobină). Așa rămâne `RELAY_ACTIVE_LOW 0` în cod.
- Dacă ai un modul "low level trigger" fără jumper, pune `#define RELAY_ACTIVE_LOW 1` în firmware (și scoate rezistorul pull-down).
- Rezistorul de 10 kΩ GPIO10→GND ține releul oprit și în cele câteva milisecunde de la pornire, înainte să ruleze codul.
- Convertor step-down de la bateria scooterului (48/60/72V) la 5V, minim 1A, cu siguranță pe intrare.
- **Nu** ține apăsat butonul BOOT în momentul alimentării (intră în mod programare).

## 2. Urcare firmware (Arduino IDE)
1. Boards Manager → instalează **esp32 by Espressif** 3.x (testat pe 3.1.3 și 3.3.12).
2. Placă: **ESP32C3 Dev Module**, *USB CDC On Boot: Enabled*.
3. Deschide `firmware/EarthwheelBoost/EarthwheelBoost.ino` → Upload.
4. În Serial Monitor (115200) vezi: `releu OPRIT la pornire` și `astept aplicatia...`.

Cod implicit: **0101010101**.

## 3. Aplicația Android (APK)
Aplicația merge pe **Android 7.0 sau mai nou** și nu folosește biblioteci externe (doar Android SDK + Kotlin).
APK-ul gata semnat (`EarthwheelBoost.apk`) se instalează direct: deschizi fișierul pe telefon → permiți „Instalare din surse necunoscute”.

**Varianta A — GitHub:** workflow-ul compilează un APK *fără cheie* (`EarthwheelBoost-unsigned.apk`); cheia se adaugă și APK-ul se semnează separat, ca cheia să nu ajungă niciodată pe GitHub.

1. Creează un repository nou pe GitHub și urcă tot folderul `earthwheel-boost` (Add file → Upload files).
2. Tab-ul **Actions** → workflow-ul *Build Earthwheel Boost APK* pornește singur (~5 min).
3. Deschide rularea → **Artifacts** → descarcă `EarthwheelBoost-APK` → instalează `EarthwheelBoost.apk` pe telefon.

**Varianta B — Android Studio:** File → Open → folderul `android` → Build → Build APK(s).

> Fiecare build GitHub e semnat cu o cheie nouă; la actualizare dezinstalează versiunea veche întâi.

## 4. Prima conectare
1. Pornește scooterul (placa). Deschide aplicația, acordă permisiunea Bluetooth.
2. Aplicația găsește placa și îți cere codul → `0101010101`.
3. Din acel moment se conectează **automat doar la placa ta** (adresa e salvată).
4. Din **Securitate**: schimbă codul (4–16 cifre), scoate codul, setează **emailul de recuperare**, blocare aplicație cu cod.

## Securitate
- Cheie unică de 256 biți, aceeași în firmware (`firmware/EarthwheelBoost/secret.h`) și în aplicație (`android/app/src/main/assets/ew_key.bin`). Aceste două fișiere **nu se publică pe GitHub** (sunt în `.gitignore`); le primești doar în arhiva privată. Alte aplicații BLE nu pot comanda placa.
- Autentificare challenge-response HMAC-SHA256: codul tău **nu circulă prin aer**.
- Fiecare comandă e semnată și numerotată (nu poate fi înregistrată și retrimisă).
- 5 coduri greșite → blocare 60 s. Conexiune neautentificată → deconectare după 45 s.
- Codul, emailul și datele Gmail sunt stocate criptat pe telefon (Android Keystore).

## Recuperare cod
- **Pe email:** „Am uitat codul – trimite pe email”. Dacă ai completat contul Gmail + *parola de aplicație* (Google Account → Securitate → Parole pentru aplicații), emailul pleacă automat. Altfel se deschide aplicația de email cu mesajul pregătit.
- **Din placă (oricând):** cu placa pornită, ține **BOOT apăsat 10 s** → LED-ul clipește → codul revine la `0101010101`, telecomanda RF se șterge.

## Telecomandă RF (ulterior)
1. Receptor 433 MHz (RXB6 recomandat) → DATA pe GPIO3, VCC 3.3V, GND.
2. Arduino IDE → Library Manager → instalează **rc-switch** (sui77).
3. În firmware: `#define ENABLE_RF 1` → Upload.
4. Aplicație → **Asociază telecomanda** → apasă butonul telecomenzii în 20 s. Un clic comută MELC/RACHETĂ, starea apare instant în aplicație.

Notă: telecomenzile 433 MHz cu cod fix (EV1527) pot fi copiate cu un scanner. Pentru siguranță maximă folosește doar aplicația sau o telecomandă cu cod rulant.

## Depanare
| Problemă | Soluție |
|---|---|
| Aplicația caută la nesfârșit | Verifică alimentarea plăcii; pe Android ≤11 pornește și Locația. Dacă ai schimbat placa: „Asociază altă placă”. |
| Releul nu cuplează | Verifică jumperul H/L și `RELAY_ACTIVE_LOW`; măsoară 3.3V pe GPIO10 în modul rachetă. |
| Releul „ciupește” la pornire | Lipsește rezistorul 10 kΩ GPIO10→GND sau modulul e low-trigger cu `RELAY_ACTIVE_LOW 0`. |
| „Blocat” | Așteaptă 60 s sau reset cod cu BOOT 10 s. |

⚠️ Pe drumurile publice, scoaterea limitatorului de viteză poate încălca omologarea vehiculului (ex. limita de 25/45 km/h). Folosește modul rachetă unde e permis.
