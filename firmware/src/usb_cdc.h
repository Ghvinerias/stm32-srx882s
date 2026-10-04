#pragma once

#include <stddef.h>
#include <stdint.h>

int usb_cdc_init(void);
void usb_cdc_poll(void);
int usb_cdc_read_byte(uint8_t *value);
int usb_cdc_write(const uint8_t *data, size_t length);
