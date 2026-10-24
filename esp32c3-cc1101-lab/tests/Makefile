# Host-side unit tests for the portable parts of the ESP32-C3 firmware.
#
# These link the same .c files the Arduino sketch builds, with no Arduino or
# ESP-IDF headers involved, mirroring the STM32 project's firmware/test_*.c
# approach.
#
#   make        build all test binaries
#   make test   build and run them

CC ?= cc
CFLAGS ?= -std=c11 -O2 -g -Wall -Wextra -Werror -I..

SRC := ../src
LIB := $(SRC)/proto_transport.c $(SRC)/lab_capture.c $(SRC)/lab_tx.c \
       $(SRC)/lab_rx.c $(SRC)/command_parse.c $(SRC)/ota_session.c

BINS := test_transport test_codeword test_capture test_command test_rxsim \
        test_app_packets test_usb_policy test_ble_link test_cc1101_regs test_hcs_reduce test_ota

.PHONY: all test clean

all: $(BINS)

test_transport: test_transport.c $(SRC)/proto_transport.c $(SRC)/lab_capture.c
	$(CC) $(CFLAGS) -o $@ $^

test_codeword: test_codeword.c $(SRC)/lab_tx.c
	$(CC) $(CFLAGS) -o $@ $^

test_capture: test_capture.c $(SRC)/lab_capture.c
	$(CC) $(CFLAGS) -o $@ $^

test_command: test_command.c $(LIB)
	$(CC) $(CFLAGS) -o $@ $^

test_app_packets: test_app_packets.c $(LIB)
	$(CC) $(CFLAGS) -o $@ $^

test_ota: test_ota.c $(LIB)
	$(CC) $(CFLAGS) -o $@ $^ -lcrypto

test_usb_policy: test_usb_policy.c $(SRC)/usb_tx_policy.c
	$(CC) $(CFLAGS) -o $@ $^

test_ble_link: test_ble_link.c $(SRC)/ble_link_state.c
	$(CC) $(CFLAGS) -o $@ $^

test_hcs_reduce: test_hcs_reduce.c $(SRC)/lab_capture.c
	$(CC) $(CFLAGS) -o $@ $^

test_cc1101_regs: test_cc1101_regs.c
	$(CC) $(CFLAGS) -o $@ $^

test_rxsim: test_rxsim.c $(SRC)/lab_rx.c
	$(CC) $(CFLAGS) -o $@ $^

test: all
	@set -e; for bin in $(BINS); do \
		echo "== $$bin =="; \
		./$$bin; \
	done

clean:
	rm -f $(BINS) *.o
