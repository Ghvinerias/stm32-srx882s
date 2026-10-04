#include "usb_cdc.h"

#include "stm32f1xx_hal.h"
#include "usbd_cdc.h"
#include "usbd_core.h"
#include "usbd_desc.h"

#define USB_RX_SIZE 256U
#define USB_TX_SIZE 512U
#define USB_RX_MASK (USB_RX_SIZE - 1U)
#define USB_TX_MASK (USB_TX_SIZE - 1U)

static USBD_HandleTypeDef usb_device;
static uint8_t usb_rx_packet[CDC_DATA_FS_OUT_PACKET_SIZE];
static uint8_t rx_ring[USB_RX_SIZE];
static uint8_t tx_ring[USB_TX_SIZE];
static volatile uint16_t rx_head, rx_tail;
static uint16_t tx_head, tx_tail, tx_inflight;
static uint8_t initialized;

static int8_t cdc_init(void) {
    USBD_CDC_SetTxBuffer(&usb_device, tx_ring, 0U);
    USBD_CDC_SetRxBuffer(&usb_device, usb_rx_packet);
    return USBD_OK;
}

static int8_t cdc_deinit(void) { return USBD_OK; }

static int8_t cdc_control(uint8_t command, uint8_t *buffer, uint16_t length) {
    (void)command;
    (void)buffer;
    (void)length;
    return USBD_OK;
}

static int8_t cdc_receive(uint8_t *buffer, uint32_t *length) {
    for (uint32_t i = 0; i < *length; ++i) {
        uint16_t next = (uint16_t)((rx_head + 1U) & USB_RX_MASK);
        if (next == rx_tail) {
            break;
        }
        rx_ring[rx_head] = buffer[i];
        rx_head = next;
    }
    USBD_CDC_SetRxBuffer(&usb_device, usb_rx_packet);
    USBD_CDC_ReceivePacket(&usb_device);
    return USBD_OK;
}

static USBD_CDC_ItfTypeDef cdc_interface = {
    cdc_init, cdc_deinit, cdc_control, cdc_receive,
};

int usb_cdc_init(void) {
    rx_head = rx_tail = tx_head = tx_tail = tx_inflight = 0U;
    if (USBD_Init(&usb_device, &RF_Lab_Desc, 0U) != USBD_OK ||
        USBD_RegisterClass(&usb_device, USBD_CDC_CLASS) != USBD_OK ||
        USBD_CDC_RegisterInterface(&usb_device, &cdc_interface) != USBD_OK ||
        USBD_Start(&usb_device) != USBD_OK) {
        return 0;
    }
    initialized = 1U;
    return 1;
}

int usb_cdc_read_byte(uint8_t *value) {
    if (!value || rx_head == rx_tail) {
        return 0;
    }
    *value = rx_ring[rx_tail];
    rx_tail = (uint16_t)((rx_tail + 1U) & USB_RX_MASK);
    return 1;
}

int usb_cdc_write(const uint8_t *data, size_t length) {
    if (!initialized || !data || length == 0U || length >= USB_TX_SIZE) {
        return 0;
    }
    uint16_t used = (uint16_t)((tx_head - tx_tail) & USB_TX_MASK);
    if ((USB_TX_SIZE - 1U - used) < length) {
        return 0;
    }
    for (size_t i = 0; i < length; ++i) {
        tx_ring[tx_head] = data[i];
        tx_head = (uint16_t)((tx_head + 1U) & USB_TX_MASK);
    }
    return 1;
}

void usb_cdc_poll(void) {
    if (!initialized || usb_device.dev_state != USBD_STATE_CONFIGURED) {
        return;
    }
    USBD_CDC_HandleTypeDef *cdc =
        (USBD_CDC_HandleTypeDef *)usb_device.pClassData;
    if (!cdc) {
        return;
    }
    if (tx_inflight != 0U) {
        if (cdc->TxState != 0U) {
            return;
        }
        tx_tail = (uint16_t)((tx_tail + tx_inflight) & USB_TX_MASK);
        tx_inflight = 0U;
    }
    if (tx_head == tx_tail) {
        return;
    }
    uint16_t length = tx_head > tx_tail
                          ? (uint16_t)(tx_head - tx_tail)
                          : (uint16_t)(USB_TX_SIZE - tx_tail);
    if (length > CDC_DATA_FS_IN_PACKET_SIZE) {
        length = CDC_DATA_FS_IN_PACKET_SIZE;
    }
    USBD_CDC_SetTxBuffer(&usb_device, &tx_ring[tx_tail], length);
    if (USBD_CDC_TransmitPacket(&usb_device) == USBD_OK) {
        tx_inflight = length;
    }
}
