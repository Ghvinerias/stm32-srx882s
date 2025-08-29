/* Minimal linker script for STM32F103C8T6 (64KB FLASH, 20KB RAM) */
MEMORY
{
  FLASH (rx)  : ORIGIN = 0x08000000, LENGTH = 64K
  RAM (xrw)   : ORIGIN = 0x20000000, LENGTH = 20K
}

_estack = ORIGIN(RAM) + LENGTH(RAM);

SECTIONS
{
  .isr_vector :
  {
    . = ALIGN(4);
    KEEP(*(.isr_vector))
    . = ALIGN(4);
  } > FLASH

  .text :
  {
    . = ALIGN(4);
    *(.text .text.*)
    *(.rodata .rodata.*)
    *(.glue_7) *(.glue_7t)
    KEEP(*(.init))
    KEEP(*(.fini))
    . = ALIGN(4);
  } > FLASH

  .ARM.extab : { *(.ARM.extab* .gnu.linkonce.armextab.*) } > FLASH
  .ARM.exidx : { *(.ARM.exidx*) } > FLASH

  .preinit_array : { KEEP (*(.preinit_array*)) } > FLASH
  .init_array    : { KEEP (*(.init_array*)) } > FLASH
  .fini_array    : { KEEP (*(.fini_array*)) } > FLASH

  _sidata = LOADADDR(.data);

  .data : AT (ADDR(.text) + SIZEOF(.text))
  {
    . = ALIGN(4);
    _sdata = .;
    *(.data .data.*)
    . = ALIGN(4);
    _edata = .;
  } > RAM

  .bss (NOLOAD) :
  {
    . = ALIGN(4);
    _sbss = .;
    *(.bss .bss.*)
    *(COMMON)
    . = ALIGN(4);
    _ebss = .;
  } > RAM

  _end = .;
}