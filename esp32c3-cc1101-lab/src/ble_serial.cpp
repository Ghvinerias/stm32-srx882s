#include "ble_serial.h"

#include <Arduino.h>
#include <BLECharacteristic.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEService.h>
#include <freertos/FreeRTOS.h>
#include <freertos/stream_buffer.h>

#include "ble_link_state.h"
#include "lab_config.h"

static BLEServer *ble_server;
static BLECharacteristic *ble_tx;
static StreamBufferHandle_t ble_rx_stream;

static ble_link_state_t link_state;
static volatile uint32_t stat_subscribe, stat_connect, stat_disconnect, stat_notify;

class LabServerCallbacks : public BLEServerCallbacks {
  public:
    void onConnect(BLEServer *server, ble_gap_conn_desc *desc) override {
        (void)server;
        stat_connect = stat_connect + 1U;
        ble_link_on_connect(&link_state, desc ? desc->conn_handle : 0U);
    }

    void onDisconnect(BLEServer *server, ble_gap_conn_desc *desc) override {
        (void)server;
        stat_disconnect = stat_disconnect + 1U;
        ble_link_on_disconnect(&link_state, desc ? desc->conn_handle : 0U);
        // The HCS301 lab wants a connection available again right away.
        BLEDevice::startAdvertising();
    }

    void onMtuChanged(BLEServer *server, ble_gap_conn_desc *desc,
                      uint16_t mtu) override {
        (void)server;
        ble_link_on_mtu(&link_state, desc ? desc->conn_handle : 0U, mtu);
    }
};

class LabRxCallbacks : public BLECharacteristicCallbacks {
  public:
    void onWrite(BLECharacteristic *characteristic) override {
        uint8_t *data = characteristic->getData();
        size_t length = characteristic->getLength();
        if (!data || length == 0U || !ble_rx_stream) {
            return;
        }
        // Never block the NimBLE host task.  A full queue means the main loop
        // is busy transmitting; dropping a whole command is preferable to
        // stalling the BLE stack.  4 KiB is many times one command packet.
        (void)xStreamBufferSend(ble_rx_stream, data, length, 0);
    }
};

// The CCCD (notify enable) belongs to the TX characteristic, so NimBLE calls
// onSubscribe on THAT characteristic's callbacks -- not the RX one.
class LabTxCallbacks : public BLECharacteristicCallbacks {
  public:
    void onSubscribe(BLECharacteristic *characteristic,
                     ble_gap_conn_desc *desc, uint16_t subValue) override {
        (void)characteristic;
        stat_subscribe = stat_subscribe + 1U;
        ble_link_on_subscribe(&link_state, desc ? desc->conn_handle : 0U, subValue);
    }
};

bool ble_serial_init(void) {
    ble_link_init(&link_state);
    ble_rx_stream = xStreamBufferCreate(4096, 1);
    if (!ble_rx_stream) {
        return false;
    }

    if (!BLEDevice::init(LAB_BLE_NAME)) {
        return false;
    }

    // Preferred ATT MTU.  247 yields 244-byte notifications; the transport is
    // a byte stream, so a long FRAME may span several notifications.
    (void)BLEDevice::setMTU(LAB_BLE_PREFERRED_MTU);

    ble_server = BLEDevice::createServer();
    if (!ble_server) {
        return false;
    }
    ble_server->setCallbacks(new LabServerCallbacks());

    BLEService *service = ble_server->createService(LAB_BLE_SERVICE_UUID);
    if (!service) {
        return false;
    }

    BLECharacteristic *rx = service->createCharacteristic(
        LAB_BLE_RX_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE |
            BLECharacteristic::PROPERTY_WRITE_NR);
    rx->setCallbacks(new LabRxCallbacks());

    ble_tx = service->createCharacteristic(LAB_BLE_TX_CHAR_UUID,
                                           BLECharacteristic::PROPERTY_NOTIFY);
    ble_tx->setCallbacks(new LabTxCallbacks());
    // NimBLE adds the 0x2902 client-characteristic-configuration descriptor
    // automatically for a NOTIFY characteristic; adding one by hand is
    // deprecated (and would duplicate it).

    service->start();

    BLEAdvertising *advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(LAB_BLE_SERVICE_UUID);
    advertising->setScanResponse(true);
    advertising->setMinPreferred(0x06);
    advertising->setMaxPreferred(0x12);
    BLEDevice::startAdvertising();
    return true;
}

bool ble_serial_connected(void) { return link_state.connected != 0U; }
bool ble_serial_subscribed(void) { return ble_link_can_notify(&link_state) != 0; }
uint16_t ble_serial_mtu(void) { return link_state.mtu; }

size_t ble_serial_read(uint8_t *data, size_t max_length) {
    if (!ble_rx_stream || !data || max_length == 0U) {
        return 0U;
    }
    return xStreamBufferReceive(ble_rx_stream, data, max_length, 0);
}

bool ble_serial_write(const uint8_t *data, size_t length) {
    if (!ble_tx || !data || length == 0U || !ble_serial_subscribed()) {
        return false;
    }

    size_t chunk = ble_link_chunk(&link_state, LAB_BLE_MAX_PAYLOAD);

    size_t offset = 0;
    while (offset < length) {
        size_t remaining = length - offset;
        size_t piece = remaining < chunk ? remaining : chunk;
        ble_tx->setValue(data + offset, piece);
        // notify() is fire-and-forget in this core (returns void).  NimBLE
        // queues the notification; a transient buffer shortage shows up as a
        // hole in the byte stream, which the COBS decoder resynchronises on at
        // the next 0x00 delimiter.
        ble_tx->notify();
        stat_notify = stat_notify + 1U;
        offset += piece;
        if (offset < length) {
            // Pace multi-chunk packets: back-to-back notify() calls can
            // exhaust NimBLE's mbufs and silently drop chunks.
            delay(1);
        }
    }
    return true;
}

void ble_serial_reset_link(void) { link_state.subscribed = 0U; }

ble_serial_stats_t ble_serial_stats(void) {
    ble_serial_stats_t s;
    s.connected = link_state.connected;
    s.subscribed = link_state.subscribed;
    s.mtu = link_state.mtu;
    s.conn_handle = link_state.conn_handle;
    s.subscribe_events = stat_subscribe;
    s.connect_events = stat_connect;
    s.disconnect_events = stat_disconnect;
    s.notifies = stat_notify;
    return s;
}
