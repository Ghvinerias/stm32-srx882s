#pragma once
#include <stdint.h>
#include <stdbool.h>

typedef struct {
    volatile uint32_t buf[512];
    volatile uint16_t head;
    volatile uint16_t tail;
} ringbuf_u32_t;

static inline void rb_init(ringbuf_u32_t* rb) { rb->head = rb->tail = 0; }
static inline bool rb_empty(const ringbuf_u32_t* rb) { return rb->head == rb->tail; }
static inline bool rb_full(const ringbuf_u32_t* rb) { return ((rb->head + 1) & 0x1FF) == rb->tail; }
bool rb_push(ringbuf_u32_t* rb, uint32_t v);
bool rb_pop(ringbuf_u32_t* rb, uint32_t* out);