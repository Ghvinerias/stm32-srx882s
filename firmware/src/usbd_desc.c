#include "usbd_core.h"
#include "usbd_desc.h"

#define USB_VID 0xCAFEU
#define USB_PID 0x4001U

static uint8_t device_desc[USB_LEN_DEV_DESC] = {
    0x12, USB_DESC_TYPE_DEVICE, 0x00, 0x02,
    0x02, 0x00, 0x00, USB_MAX_EP0_SIZE,
    LOBYTE(USB_VID), HIBYTE(USB_VID), LOBYTE(USB_PID), HIBYTE(USB_PID),
    0x00, 0x01, USBD_IDX_MFC_STR, USBD_IDX_PRODUCT_STR,
    USBD_IDX_SERIAL_STR, USBD_MAX_NUM_CONFIGURATION,
};
static uint8_t lang_desc[USB_LEN_LANGID_STR_DESC] = {
    USB_LEN_LANGID_STR_DESC, USB_DESC_TYPE_STRING, 0x09, 0x04,
};
static uint8_t string_desc[USBD_MAX_STR_DESC_SIZ];

static uint8_t *device(USBD_SpeedTypeDef speed, uint16_t *length) {
    (void)speed; *length = sizeof(device_desc); return device_desc;
}
static uint8_t *lang(USBD_SpeedTypeDef speed, uint16_t *length) {
    (void)speed; *length = sizeof(lang_desc); return lang_desc;
}
static uint8_t *make_string(const char *value, uint16_t *length) {
    USBD_GetString((uint8_t *)value, string_desc, length); return string_desc;
}
static uint8_t *manufacturer(USBD_SpeedTypeDef speed, uint16_t *length) {
    (void)speed; return make_string("EE Lab", length);
}
static uint8_t *product(USBD_SpeedTypeDef speed, uint16_t *length) {
    (void)speed; return make_string("Blue Pill RF Lab", length);
}
static uint8_t *serial(USBD_SpeedTypeDef speed, uint16_t *length) {
    (void)speed; return make_string("RF433-001", length);
}
static uint8_t *configuration(USBD_SpeedTypeDef speed, uint16_t *length) {
    (void)speed; return make_string("CDC Console", length);
}
static uint8_t *interface(USBD_SpeedTypeDef speed, uint16_t *length) {
    (void)speed; return make_string("RF Lab Console", length);
}

USBD_DescriptorsTypeDef RF_Lab_Desc = {
    device, lang, manufacturer, product, serial, configuration, interface,
};
