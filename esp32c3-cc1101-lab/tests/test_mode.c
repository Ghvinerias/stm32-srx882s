// Radio mode manager + STATUS mode bits.
#include "src/proto_transport.h"
#include "src/radio_mode.h"

#include "test_util.h"

static unsigned applies, changes, aborts;
static uint8_t last_base, last_mode;

static void ap(void *c, uint8_t b) { (void)c; ++applies; last_base = b; }
static void ch(void *c, uint8_t m) { (void)c; ++changes; last_mode = m; }
static void ab(void *c) { (void)c; ++aborts; }

static uint16_t status_flags(rec_t *rec) {
    uint8_t raw[64];
    size_t n = proto_cobs_decode(rec->data, rec->length - 1U, raw, sizeof(raw));
    return n >= 4U ? (uint16_t)(raw[2] | (raw[3] << 8U)) : 0xFFFFU;
}

int main(void) {
    radio_mode_ops_t ops = {ap, ch, ab, 0};
    radio_mode_init(&ops);
    expect("boots in IDLE", radio_mode_current() == RADIO_MODE_IDLE &&
                                radio_mode_base() == RADIO_MODE_IDLE);
    expect("init calls no callbacks", applies == 0U && changes == 0U);

    expect("request RX accepted", radio_mode_request(RADIO_MODE_RX) == 1);
    expect("RX applied and reported", applies == 1U && last_base == RADIO_MODE_RX &&
                                          changes == 1U && last_mode == RADIO_MODE_RX);
    expect("unknown mode rejected", radio_mode_request(3U) == 0 && radio_mode_request(255U) == 0);
    expect("rejected requests change nothing", applies == 1U && changes == 1U &&
                                                   radio_mode_current() == RADIO_MODE_RX);

    radio_mode_tx_begin();
    expect("TX is reported as the current mode",
           radio_mode_current() == RADIO_MODE_TX && last_mode == RADIO_MODE_TX &&
               radio_mode_base() == RADIO_MODE_RX);
    expect("MODE rx during TX is accepted and deferred",
           radio_mode_request(RADIO_MODE_RX) == 1 && applies == 1U && aborts == 0U);
    expect("MODE idle during TX aborts the burst",
           radio_mode_request(RADIO_MODE_IDLE) == 1 && aborts == 1U && applies == 1U);
    radio_mode_tx_end();
    expect("TX end restores the (latest) base mode and reports it",
           applies == 2U && last_base == RADIO_MODE_IDLE && last_mode == RADIO_MODE_IDLE &&
               radio_mode_current() == RADIO_MODE_IDLE);

    expect("mode names", radio_mode_name(0U)[0] == 'i' && radio_mode_name(1U)[0] == 'r' &&
                             radio_mode_name(2U)[0] == 't');

    // ---- STATUS carries the mode in flags bits 2..3 ----
    link_ctx_t links;
    memset(&links, 0, sizeof(links));
    links.deliver_mask = 0x03U;
    proto_sink_t sink = {link_write, &links};
    proto_init(&sink, PROTO_FLAG_CAPTURE_INIT_FAILED);
    proto_send_status(1000000U, 0U, 0U, PROTO_LINK_USB);
    uint16_t flags = status_flags(&links.usb);
    expect("STATUS after boot reports IDLE (bits 2..3 = 0), boot flag kept",
           (flags & PROTO_FLAG_MODE_MASK) == 0U && (flags & PROTO_FLAG_CAPTURE_INIT_FAILED));
    for (uint8_t m = 1U; m <= 2U; ++m) {
        rec_reset(&links.usb);
        proto_set_mode(m);
        proto_send_status(1000000U, 0U, 0U, PROTO_LINK_USB);
        flags = status_flags(&links.usb);
        char label[64];
        snprintf(label, sizeof(label), "STATUS mode %u -> flags bits 2..3 = %u, other bits intact", m, m);
        expect(label, ((flags & PROTO_FLAG_MODE_MASK) >> PROTO_FLAG_MODE_SHIFT) == m &&
                          (flags & PROTO_FLAG_CAPTURE_INIT_FAILED) &&
                          (flags & ~(PROTO_FLAG_MODE_MASK | PROTO_FLAG_CAPTURE_INIT_FAILED)) == 0U);
    }
    expect("mode bits sit at flags 0x000C", PROTO_FLAG_MODE_MASK == 0x000CU && PROTO_FLAG_MODE_SHIFT == 2U);
    return test_result();
}
