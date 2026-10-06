/*
  ============================================================
   EARTHWHEEL BOOST  —  firmware ESP32-C3 Super Mini
  ============================================================
   Comandă un releu 5V (trepte viteză scooter) din aplicația
   Android "Earthwheel Boost" prin Bluetooth Low Energy (BLE).

   REGULI DE SIGURANȚĂ (garantate de cod):
   - La FIECARE pornire releul este OPRIT (mod MELC 🐌).
     Starea NU se salvează; nu se cuplează niciodată singur.
   - Releul se cuplează DOAR la comanda din aplicație
     (sau din telecomanda RF, dacă o activezi și o asociezi).
   - Rămâne cuplat cât timp placa e alimentată sau până îl
     oprești din aplicație. Pierderea conexiunii BLE NU schimbă starea.

   SECURITATE:
   - Se conectează doar aplicația care are cheia APP_SECRET
     (aceeași cheie e compilată în APK) + codul PIN.
   - Autentificare challenge-response HMAC-SHA256 (PIN-ul nu
     circulă niciodată prin aer), comenzi semnate + anti-replay.
   - 5 încercări greșite => blocare 60 s.
   - Client neautentificat => deconectat după 45 s.

   RESET COD (dacă l-ai uitat și nu ai acces la email):
   - Cu placa PORNITĂ, ține apăsat butonul BOOT 10 secunde.
     LED-ul clipește => codul revine la 0101010101,
     telecomanda RF asociată se șterge.
     (NU ține BOOT apăsat în momentul alimentării — intră în mod programare.)

   Arduino IDE:
   - Board: "ESP32C3 Dev Module" (pachet esp32 by Espressif 3.x; testat pe 3.1.3 și 3.3.12)
   - USB CDC On Boot: Enabled (pentru Serial Monitor)
   - Partition Scheme: "Default 4MB" (merge, ocupă 49-84%)
   - Pentru RF: setează ENABLE_RF 1 și instalează biblioteca
     "rc-switch" (sui77) din Library Manager.
  ============================================================ */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Preferences.h>
#include "driver/gpio.h"
#include "esp_system.h"
#if __has_include("esp_random.h")
#include "esp_random.h"
#endif
#include "mbedtls/md.h"

// ================== CONFIGURARE HARDWARE ==================
#define RELAY_PIN          10     // GPIO10: fără pull-up intern la reset (sigur la pornire)
#define RELAY_ACTIVE_LOW   0      // 0 = modul releu cu declanșare pe HIGH / tranzistor NPN (RECOMANDAT)
                                  // 1 = modul releu "low level trigger" (IN=LOW => cuplat)
#define LED_PIN            8      // LED albastru de pe Super Mini (activ pe LOW) = arată modul rachetă
#define BOOT_BTN_PIN       9      // butonul BOOT de pe placă (reset cod)

#define ENABLE_RF          0      // 1 = activează receptor RF 433MHz (RXB6 / SRX882 etc.)
#define RF_PIN             3      // GPIO3: pinul DATA al receptorului RF

#define DEFAULT_PIN        "0101010101"
#define BLE_NAME           "EW-B" // nume scurt (încape în pachetul de advertising)

// ================== CHEIA APLICAȚIEI ==================
// Cheia unică (32 octeți) e în secret.h, ținut în afara GitHub.
// Trebuie să fie identică cu android/app/src/main/assets/ew_key.bin din aplicație.
#include "secret.h"

// ================== UUID-uri BLE (identice în aplicație) ==================
#define SVC_UUID        "00086dbb-4217-402e-9eec-18ebe775b223"
#define STATUS_UUID     "ea66f4fc-2f2a-446d-b42c-aa629a27d0c9"   // read + notify
#define CHALLENGE_UUID  "fb72bf2d-c29a-449f-86ba-081977ee7cef"   // read
#define AUTH_UUID       "fe5dcef1-fdce-41ed-8564-8f6b67a79d1d"   // write
#define CMD_UUID        "33e96412-2f79-4b7e-bc65-ce22418c2335"   // write

// ================== PROTOCOL ==================
#define PROTO_VERSION   1
// coduri comandă
#define OP_RELAY_SET    0x01
#define OP_RELAY_TOGGLE 0x02
#define OP_PIN_SET      0x10
#define OP_PIN_REMOVE   0x11
#define OP_RF_LEARN     0x20
#define OP_RF_CLEAR     0x21
#define OP_RF_CANCEL    0x22
#define OP_STATUS       0x30
// coduri rezultat (status[1])
#define RES_NONE        0
#define RES_AUTH_OK     1
#define RES_AUTH_FAIL   2
#define RES_LOCKED      3
#define RES_BAD_MAC     4
#define RES_NOT_AUTH    5
#define RES_PIN_OK      6
#define RES_PIN_REMOVED 7
#define RES_RF_LEARNED  8
#define RES_RF_CLEARED  9
#define RES_RF_DISABLED 10
#define RES_BAD_CMD     11
#define RES_RELAY       12
#define RES_RF_TIMEOUT  13
#define RES_RF_LEARNING 14
#define RES_HEARTBEAT   15
// bit-uri flags (status[0])
#define F_AUTH          0x01
#define F_RELAY         0x02
#define F_PINSET        0x04
#define F_RF_ENABLED    0x08
#define F_RF_LEARNED    0x10
#define F_RF_LEARNING   0x20

#define MAX_FAILS          5
#define LOCK_MS            60000UL
#define UNAUTH_TIMEOUT_MS  45000UL
#define HEARTBEAT_MS       3000UL
#define RF_LEARN_MS        20000UL
#define BOOT_RESET_MS      10000UL

#if ENABLE_RF
#include <RCSwitch.h>
RCSwitch rfRx;
#endif

// ================== STARE ==================
Preferences prefs;
String   pinCode;            // "" = fără cod
bool     relayOn = false;

BLEServer*         server       = nullptr;
BLECharacteristic* statusChar   = nullptr;
BLECharacteristic* challengeChar= nullptr;
BLECharacteristic* authChar     = nullptr;
BLECharacteristic* cmdChar      = nullptr;

volatile bool     clientConnected = false;
volatile uint32_t connectedAt     = 0;
volatile uint32_t restartAdvAt    = 0;
uint32_t          disconnectAt    = 0;

bool     authed  = false;
uint8_t  nonce[16];
uint8_t  sessKey[32];
uint32_t lastSeq = 0;
uint8_t  failCount = 0;
uint32_t lockUntil = 0;
uint32_t lastHeartbeat = 0;

// buffer comenzi primite (procesate în loop, nu în task-ul BLE)
portMUX_TYPE rxMux = portMUX_INITIALIZER_UNLOCKED;
uint8_t  authBuf[64]; volatile size_t authLen = 0; volatile bool authPending = false;
uint8_t  cmdBuf[64];  volatile size_t cmdLen  = 0; volatile bool cmdPending  = false;

// RF
bool          rfLearned  = false;
bool          rfLearning = false;
uint32_t      rfLearnUntil = 0;
unsigned long rfCode = 0;
unsigned int  rfBits = 0;
uint32_t      rfLastSeen = 0;

// ================== RELEU ==================
static inline int relayLevel(bool on) {
  return RELAY_ACTIVE_LOW ? (on ? 0 : 1) : (on ? 1 : 0);
}

void relayHardwareInitOff() {
  // 1) scriem nivelul OPRIT în registru ÎNAINTE ca pinul să devină ieșire
  gpio_set_level((gpio_num_t)RELAY_PIN, relayLevel(false));
  gpio_config_t cfg = {};
  cfg.pin_bit_mask = (1ULL << RELAY_PIN);
  cfg.mode         = GPIO_MODE_OUTPUT;
  cfg.pull_up_en   = RELAY_ACTIVE_LOW ? GPIO_PULLUP_ENABLE : GPIO_PULLUP_DISABLE;
  cfg.pull_down_en = RELAY_ACTIVE_LOW ? GPIO_PULLDOWN_DISABLE : GPIO_PULLDOWN_ENABLE;
  cfg.intr_type    = GPIO_INTR_DISABLE;
  gpio_config(&cfg);
  // 2) confirmăm OPRIT
  gpio_set_level((gpio_num_t)RELAY_PIN, relayLevel(false));
  relayOn = false;
}

void ledWrite(bool on) { gpio_set_level((gpio_num_t)LED_PIN, on ? 0 : 1); }

void relayWrite(bool on) {
  relayOn = on;
  gpio_set_level((gpio_num_t)RELAY_PIN, relayLevel(on));
  ledWrite(on);
  Serial.printf("[RELEU] %s\n", on ? "CUPLAT (RACHETA)" : "OPRIT (MELC)");
}

// ================== CRIPTO ==================
static void hmac256(const uint8_t* key, size_t klen,
                    const uint8_t* const* parts, const size_t* lens, int n,
                    uint8_t out[32]) {
  mbedtls_md_context_t ctx;
  mbedtls_md_init(&ctx);
  mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 1);
  mbedtls_md_hmac_starts(&ctx, key, klen);
  for (int i = 0; i < n; i++) if (lens[i]) mbedtls_md_hmac_update(&ctx, parts[i], lens[i]);
  mbedtls_md_hmac_finish(&ctx, out);
  mbedtls_md_free(&ctx);
}

static bool ctEqual(const uint8_t* a, const uint8_t* b, size_t n) {
  uint8_t d = 0;
  for (size_t i = 0; i < n; i++) d |= a[i] ^ b[i];
  return d == 0;
}

// K = HMAC(APP_SECRET, "EWK1" || nonce || pin)
static void deriveKey(uint8_t out[32]) {
  const uint8_t* parts[3] = { (const uint8_t*)"EWK1", nonce, (const uint8_t*)pinCode.c_str() };
  size_t lens[3] = { 4, sizeof(nonce), pinCode.length() };
  hmac256(APP_SECRET, sizeof(APP_SECRET), parts, lens, 3, out);
}

// ================== STATUS ==================
void pushStatus(uint8_t res, uint8_t extra = 0) {
  uint8_t flags = 0;
  if (authed)               flags |= F_AUTH;
  if (authed && relayOn)    flags |= F_RELAY;
  if (pinCode.length() > 0) flags |= F_PINSET;
#if ENABLE_RF
  flags |= F_RF_ENABLED;
  if (authed && rfLearned)  flags |= F_RF_LEARNED;
  if (authed && rfLearning) flags |= F_RF_LEARNING;
#endif
  uint8_t st[4] = { flags, res, extra, PROTO_VERSION };
  statusChar->setValue(st, sizeof(st));
  if (clientConnected) statusChar->notify();
  lastHeartbeat = millis();
}

void newNonce() {
  esp_fill_random(nonce, sizeof(nonce));
  challengeChar->setValue(nonce, sizeof(nonce));
}

void wipeSession() {
  authed = false;
  lastSeq = 0;
  memset(sessKey, 0, sizeof(sessKey));
}

// ================== PERSISTENȚĂ ==================
void loadSettings() {
  prefs.begin("ewb", false);
  if (!prefs.isKey("init")) {           // prima pornire
    prefs.putBool("init", true);
    prefs.putBool("haspin", true);
    prefs.putString("pin", DEFAULT_PIN);
  }
  pinCode   = prefs.getBool("haspin", true) ? prefs.getString("pin", DEFAULT_PIN) : String("");
  rfLearned = prefs.getBool("rfok", false);
  rfCode    = prefs.getULong("rfcode", 0);
  rfBits    = prefs.getUInt("rfbits", 0);
  // NOTĂ: starea releului NU se salvează niciodată (pornește mereu OPRIT).
}

void savePin(const String& p) {
  pinCode = p;
  prefs.putBool("haspin", p.length() > 0);
  prefs.putString("pin", p);
}

void clearRf() {
  rfLearned = false; rfCode = 0; rfBits = 0;
  prefs.putBool("rfok", false);
  prefs.putULong("rfcode", 0);
  prefs.putUInt("rfbits", 0);
}

// ================== CALLBACK-uri BLE ==================
class ServerCB : public BLEServerCallbacks {
  void onConnect(BLEServer* s) override {
    clientConnected = true;
    connectedAt = millis();
    wipeSession();
    newNonce();
    pushStatus(RES_NONE);   // status proaspăt (fără rezultate vechi din sesiunea anterioară)
    Serial.println("[BLE] client conectat");
  }
  void onDisconnect(BLEServer* s) override {
    clientConnected = false;
    wipeSession();
    restartAdvAt = millis() + 300;
    if (restartAdvAt == 0) restartAdvAt = 1;
    Serial.println("[BLE] client deconectat (releul își păstrează starea)");
  }
};

class AuthCB : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    size_t n = c->getLength();
    if (n == 0 || n > sizeof(authBuf)) return;
    portENTER_CRITICAL(&rxMux);
    memcpy(authBuf, c->getData(), n); authLen = n; authPending = true;
    portEXIT_CRITICAL(&rxMux);
  }
};

class CmdCB : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    size_t n = c->getLength();
    if (n == 0 || n > sizeof(cmdBuf)) return;
    portENTER_CRITICAL(&rxMux);
    memcpy(cmdBuf, c->getData(), n); cmdLen = n; cmdPending = true;
    portEXIT_CRITICAL(&rxMux);
  }
};

// ================== PROCESARE ==================
void handleAuth(const uint8_t* d, size_t n) {
  uint32_t now = millis();
  if (lockUntil && (int32_t)(lockUntil - now) > 0) {
    pushStatus(RES_LOCKED, (uint8_t)((lockUntil - now) / 1000 + 1));
    disconnectAt = now + 400;
    return;
  }
  lockUntil = 0;
  if (n != 32) { pushStatus(RES_AUTH_FAIL, MAX_FAILS - failCount); return; }

  uint8_t k[32], expect[32];
  deriveKey(k);
  const uint8_t* parts[1] = { (const uint8_t*)"AUTH" };
  size_t lens[1] = { 4 };
  hmac256(k, 32, parts, lens, 1, expect);

  if (ctEqual(expect, d, 32)) {
    memcpy(sessKey, k, 32);
    authed = true;
    lastSeq = 0;
    failCount = 0;
    Serial.println("[AUTH] OK");
    pushStatus(RES_AUTH_OK);
  } else {
    failCount++;
    Serial.printf("[AUTH] cod gresit (%u/%u)\n", failCount, MAX_FAILS);
    if (failCount >= MAX_FAILS) {
      failCount = 0;
      lockUntil = now + LOCK_MS;
      if (lockUntil == 0) lockUntil = 1;
      pushStatus(RES_LOCKED, LOCK_MS / 1000);
      disconnectAt = now + 400;
    } else {
      newNonce();                                   // provocare nouă pt. următoarea încercare
      pushStatus(RES_AUTH_FAIL, MAX_FAILS - failCount);
    }
  }
  memset(k, 0, sizeof(k));
}

// pachet: [op 1][seq 4 LE][payload 0..N][mac 16] ; mac = HMAC(K, op||seq||payload)[0..15]
void handleCmd(const uint8_t* d, size_t n) {
  if (!authed) { pushStatus(RES_NOT_AUTH); return; }
  if (n < 1 + 4 + 16) { pushStatus(RES_BAD_CMD); return; }

  uint8_t op = d[0];
  uint32_t seq = (uint32_t)d[1] | ((uint32_t)d[2] << 8) | ((uint32_t)d[3] << 16) | ((uint32_t)d[4] << 24);
  const uint8_t* payload = d + 5;
  size_t plen = n - 5 - 16;
  const uint8_t* mac = d + n - 16;

  uint8_t full[32];
  const uint8_t* parts[3] = { &op, d + 1, payload };
  size_t lens[3] = { 1, 4, plen };
  hmac256(sessKey, 32, parts, lens, 3, full);
  if (!ctEqual(full, mac, 16) || seq <= lastSeq) { pushStatus(RES_BAD_MAC); return; }
  lastSeq = seq;

  switch (op) {
    case OP_RELAY_SET:
      if (plen != 1) { pushStatus(RES_BAD_CMD); return; }
      relayWrite(payload[0] != 0);
      pushStatus(RES_RELAY);
      break;

    case OP_RELAY_TOGGLE:
      relayWrite(!relayOn);
      pushStatus(RES_RELAY);
      break;

    case OP_PIN_SET: {
      if (plen < 4 || plen > 16) { pushStatus(RES_BAD_CMD); return; }
      // cheie de decriptare: HMAC(K, "PIN" || seq)
      uint8_t ks[32];
      const uint8_t* p2[2] = { (const uint8_t*)"PIN", d + 1 };
      size_t l2[2] = { 3, 4 };
      hmac256(sessKey, 32, p2, l2, 2, ks);
      char buf[17];
      for (size_t i = 0; i < plen; i++) {
        buf[i] = (char)(payload[i] ^ ks[i]);
        if (buf[i] < '0' || buf[i] > '9') { pushStatus(RES_BAD_CMD); return; }
      }
      buf[plen] = 0;
      savePin(String(buf));
      memset(buf, 0, sizeof(buf));
      Serial.println("[PIN] cod schimbat");
      pushStatus(RES_PIN_OK);
      break;
    }

    case OP_PIN_REMOVE:
      savePin("");
      Serial.println("[PIN] cod eliminat");
      pushStatus(RES_PIN_REMOVED);
      break;

    case OP_RF_LEARN:
#if ENABLE_RF
      rfLearning = true;
      rfLearnUntil = millis() + RF_LEARN_MS;
      pushStatus(RES_RF_LEARNING, RF_LEARN_MS / 1000);
#else
      pushStatus(RES_RF_DISABLED);
#endif
      break;

    case OP_RF_CLEAR:
#if ENABLE_RF
      rfLearning = false;
      clearRf();
      pushStatus(RES_RF_CLEARED);
#else
      pushStatus(RES_RF_DISABLED);
#endif
      break;

    case OP_RF_CANCEL:
      rfLearning = false;
      pushStatus(RES_NONE);
      break;

    case OP_STATUS:
      pushStatus(RES_NONE);
      break;

    default:
      pushStatus(RES_BAD_CMD);
  }
}

// ================== RF ==================
void rfLoop() {
#if ENABLE_RF
  if (rfLearning && (int32_t)(millis() - rfLearnUntil) > 0) {
    rfLearning = false;
    pushStatus(RES_RF_TIMEOUT);
  }
  if (!rfRx.available()) return;
  unsigned long v = rfRx.getReceivedValue();
  unsigned int  b = rfRx.getReceivedBitlength();
  rfRx.resetAvailable();
  if (v == 0) return;

  uint32_t now = millis();
  bool newPress = (now - rfLastSeen) > 600;   // telecomanda repetă codul cât ții apăsat
  rfLastSeen = now;

  if (rfLearning) {
    rfLearning = false;
    rfCode = v; rfBits = b; rfLearned = true;
    prefs.putBool("rfok", true);
    prefs.putULong("rfcode", rfCode);
    prefs.putUInt("rfbits", rfBits);
    Serial.printf("[RF] telecomanda asociata: %lu (%u biti)\n", v, b);
    pushStatus(RES_RF_LEARNED);
    return;
  }
  if (newPress && rfLearned && v == rfCode && b == rfBits) {
    relayWrite(!relayOn);
    pushStatus(RES_RELAY);
  }
#endif
}

// ================== BUTON BOOT (reset cod) ==================
void bootButtonLoop() {
  static uint32_t pressedAt = 0;
  static bool done = false;
  if (digitalRead(BOOT_BTN_PIN) == LOW) {
    if (!pressedAt) pressedAt = millis();
    if (!done && millis() - pressedAt > BOOT_RESET_MS) {
      done = true;
      savePin(DEFAULT_PIN);
      clearRf();
      failCount = 0; lockUntil = 0;
      Serial.println("[RESET] cod resetat la implicit, RF sters");
      for (int i = 0; i < 6; i++) { ledWrite(i % 2 == 0); delay(150); }
      ledWrite(relayOn);
      if (clientConnected) disconnectAt = millis() + 100;
    }
  } else {
    pressedAt = 0;
    done = false;
  }
}

// ================== SETUP / LOOP ==================
void setup() {
  // PRIMUL LUCRU: releul garantat OPRIT
  relayHardwareInitOff();

  gpio_set_level((gpio_num_t)LED_PIN, 1);
  pinMode(LED_PIN, OUTPUT);
  ledWrite(false);
  pinMode(BOOT_BTN_PIN, INPUT_PULLUP);

  Serial.begin(115200);
  delay(50);
  Serial.println("\n=== Earthwheel Boost === releu OPRIT la pornire");

  loadSettings();

#if ENABLE_RF
  rfRx.enableReceive(digitalPinToInterrupt(RF_PIN));
#endif

  BLEDevice::init(BLE_NAME);
  BLEDevice::setMTU(185);
  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCB());

  BLEService* svc = server->createService(SVC_UUID);

  statusChar = svc->createCharacteristic(STATUS_UUID,
                 BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
#if !defined(CONFIG_NIMBLE_ENABLED)
  statusChar->addDescriptor(new BLE2902());   // Bluedroid (core 2.x / 3.0-3.2); NimBLE (core 3.3+) îl adaugă singur
#endif

  challengeChar = svc->createCharacteristic(CHALLENGE_UUID, BLECharacteristic::PROPERTY_READ);

  authChar = svc->createCharacteristic(AUTH_UUID, BLECharacteristic::PROPERTY_WRITE);
  authChar->setCallbacks(new AuthCB());

  cmdChar = svc->createCharacteristic(CMD_UUID, BLECharacteristic::PROPERTY_WRITE);
  cmdChar->setCallbacks(new CmdCB());

  newNonce();
  pushStatus(RES_NONE);
  svc->start();

  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SVC_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("[BLE] astept aplicatia...");
}

void loop() {
  uint32_t now = millis();

  // comenzi primite
  if (authPending) {
    uint8_t buf[64]; size_t n;
    portENTER_CRITICAL(&rxMux);
    n = authLen; memcpy(buf, authBuf, n); authPending = false;
    portEXIT_CRITICAL(&rxMux);
    if (clientConnected) handleAuth(buf, n);
  }
  if (cmdPending) {
    uint8_t buf[64]; size_t n;
    portENTER_CRITICAL(&rxMux);
    n = cmdLen; memcpy(buf, cmdBuf, n); cmdPending = false;
    portEXIT_CRITICAL(&rxMux);
    if (clientConnected) handleCmd(buf, n);
  }

  // client neautentificat prea mult timp => afară
  if (clientConnected && !authed && (now - connectedAt) > UNAUTH_TIMEOUT_MS && !disconnectAt) {
    Serial.println("[BLE] client neautentificat - deconectez");
    disconnectAt = now;
  }
  if (disconnectAt && (int32_t)(now - disconnectAt) >= 0) {
    disconnectAt = 0;
    if (clientConnected) server->disconnect(server->getConnId());
  }

  // reluare advertising după deconectare
  if (restartAdvAt && (int32_t)(now - restartAdvAt) >= 0) {
    restartAdvAt = 0;
    BLEDevice::startAdvertising();
  }

  // heartbeat ca aplicația să știe că legătura e vie
  if (clientConnected && authed && now - lastHeartbeat > HEARTBEAT_MS) pushStatus(RES_HEARTBEAT);

  rfLoop();
  bootButtonLoop();
  delay(5);
}
