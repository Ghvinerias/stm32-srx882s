#include "ringbuf.h"

bool rb_push(ringbuf_u32_t* rb, uint32_t v)
{
    uint16_t next = (rb->head + 1) & 0x1FF;
    if (next == rb->tail) return false; // full
    rb->buf[rb->head] = v;
    rb->head = next;
    return true;
}

bool rb_pop(ringbuf_u32_t* rb, uint32_t* out)
{
    if (rb->head == rb->tail) return false;
    *out = rb->buf[rb->tail];
    rb->tail = (rb->tail + 1) & 0x1FF;
    return true;
}