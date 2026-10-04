#include "stm32f1xx_hal.h"
#include "usbd_core.h"

static PCD_HandleTypeDef hpcd_usb;

void HAL_PCD_MspInit(PCD_HandleTypeDef *hpcd) {
    if (hpcd->Instance != USB) return;
    __HAL_RCC_USB_CLK_ENABLE();
    HAL_NVIC_SetPriority(USB_LP_CAN1_RX0_IRQn, 3U, 0U);
    HAL_NVIC_EnableIRQ(USB_LP_CAN1_RX0_IRQn);
}

void HAL_PCD_MspDeInit(PCD_HandleTypeDef *hpcd) {
    if (hpcd->Instance != USB) return;
    HAL_NVIC_DisableIRQ(USB_LP_CAN1_RX0_IRQn);
    __HAL_RCC_USB_CLK_DISABLE();
}

void USB_LP_CAN1_RX0_IRQHandler(void) { HAL_PCD_IRQHandler(&hpcd_usb); }

void HAL_PCD_SetupStageCallback(PCD_HandleTypeDef *hpcd) {
    USBD_LL_SetupStage(hpcd->pData, (uint8_t *)hpcd->Setup);
}
void HAL_PCD_DataOutStageCallback(PCD_HandleTypeDef *hpcd, uint8_t epnum) {
    USBD_LL_DataOutStage(hpcd->pData, epnum, hpcd->OUT_ep[epnum].xfer_buff);
}
void HAL_PCD_DataInStageCallback(PCD_HandleTypeDef *hpcd, uint8_t epnum) {
    USBD_LL_DataInStage(hpcd->pData, epnum, hpcd->IN_ep[epnum].xfer_buff);
}
void HAL_PCD_SOFCallback(PCD_HandleTypeDef *hpcd) { USBD_LL_SOF(hpcd->pData); }
void HAL_PCD_ResetCallback(PCD_HandleTypeDef *hpcd) {
    USBD_LL_Reset(hpcd->pData);
    USBD_LL_SetSpeed(hpcd->pData, USBD_SPEED_FULL);
}
void HAL_PCD_SuspendCallback(PCD_HandleTypeDef *hpcd) { USBD_LL_Suspend(hpcd->pData); }
void HAL_PCD_ResumeCallback(PCD_HandleTypeDef *hpcd) { USBD_LL_Resume(hpcd->pData); }
void HAL_PCD_ISOOUTIncompleteCallback(PCD_HandleTypeDef *hpcd, uint8_t epnum) {
    USBD_LL_IsoOUTIncomplete(hpcd->pData, epnum);
}
void HAL_PCD_ISOINIncompleteCallback(PCD_HandleTypeDef *hpcd, uint8_t epnum) {
    USBD_LL_IsoINIncomplete(hpcd->pData, epnum);
}
void HAL_PCD_ConnectCallback(PCD_HandleTypeDef *hpcd) { USBD_LL_DevConnected(hpcd->pData); }
void HAL_PCD_DisconnectCallback(PCD_HandleTypeDef *hpcd) { USBD_LL_DevDisconnected(hpcd->pData); }

USBD_StatusTypeDef USBD_LL_Init(USBD_HandleTypeDef *pdev) {
    hpcd_usb.Instance = USB;
    hpcd_usb.Init.dev_endpoints = 4U;
    hpcd_usb.Init.speed = PCD_SPEED_FULL;
    hpcd_usb.Init.ep0_mps = EP_MPS_64;
    hpcd_usb.Init.phy_itface = PCD_PHY_EMBEDDED;
    hpcd_usb.Init.low_power_enable = 0U;
    hpcd_usb.Init.lpm_enable = 0U;
    hpcd_usb.Init.battery_charging_enable = 0U;
    hpcd_usb.Init.Sof_enable = 0U;
    hpcd_usb.pData = pdev;
    pdev->pData = &hpcd_usb;
    if (HAL_PCD_Init(&hpcd_usb) != HAL_OK) return USBD_FAIL;

    HAL_PCDEx_PMAConfig(&hpcd_usb, 0x00U, PCD_SNG_BUF, 0x18U);
    HAL_PCDEx_PMAConfig(&hpcd_usb, 0x80U, PCD_SNG_BUF, 0x58U);
    HAL_PCDEx_PMAConfig(&hpcd_usb, 0x81U, PCD_SNG_BUF, 0xC0U);
    HAL_PCDEx_PMAConfig(&hpcd_usb, 0x82U, PCD_SNG_BUF, 0x100U);
    HAL_PCDEx_PMAConfig(&hpcd_usb, 0x01U, PCD_SNG_BUF, 0x110U);
    return USBD_OK;
}
USBD_StatusTypeDef USBD_LL_DeInit(USBD_HandleTypeDef *pdev) {
    return HAL_PCD_DeInit(pdev->pData) == HAL_OK ? USBD_OK : USBD_FAIL;
}
USBD_StatusTypeDef USBD_LL_Start(USBD_HandleTypeDef *pdev) {
    return HAL_PCD_Start(pdev->pData) == HAL_OK ? USBD_OK : USBD_FAIL;
}
USBD_StatusTypeDef USBD_LL_Stop(USBD_HandleTypeDef *pdev) {
    return HAL_PCD_Stop(pdev->pData) == HAL_OK ? USBD_OK : USBD_FAIL;
}
USBD_StatusTypeDef USBD_LL_OpenEP(USBD_HandleTypeDef *pdev, uint8_t addr,
                                  uint8_t type, uint16_t mps) {
    return HAL_PCD_EP_Open(pdev->pData, addr, mps, type) == HAL_OK ? USBD_OK : USBD_FAIL;
}
USBD_StatusTypeDef USBD_LL_CloseEP(USBD_HandleTypeDef *pdev, uint8_t addr) {
    return HAL_PCD_EP_Close(pdev->pData, addr) == HAL_OK ? USBD_OK : USBD_FAIL;
}
USBD_StatusTypeDef USBD_LL_FlushEP(USBD_HandleTypeDef *pdev, uint8_t addr) {
    return HAL_PCD_EP_Flush(pdev->pData, addr) == HAL_OK ? USBD_OK : USBD_FAIL;
}
USBD_StatusTypeDef USBD_LL_StallEP(USBD_HandleTypeDef *pdev, uint8_t addr) {
    return HAL_PCD_EP_SetStall(pdev->pData, addr) == HAL_OK ? USBD_OK : USBD_FAIL;
}
USBD_StatusTypeDef USBD_LL_ClearStallEP(USBD_HandleTypeDef *pdev, uint8_t addr) {
    return HAL_PCD_EP_ClrStall(pdev->pData, addr) == HAL_OK ? USBD_OK : USBD_FAIL;
}
uint8_t USBD_LL_IsStallEP(USBD_HandleTypeDef *pdev, uint8_t addr) {
    PCD_HandleTypeDef *h = pdev->pData;
    return (addr & 0x80U) ? h->IN_ep[addr & 0x7FU].is_stall
                          : h->OUT_ep[addr & 0x7FU].is_stall;
}
USBD_StatusTypeDef USBD_LL_SetUSBAddress(USBD_HandleTypeDef *pdev, uint8_t addr) {
    return HAL_PCD_SetAddress(pdev->pData, addr) == HAL_OK ? USBD_OK : USBD_FAIL;
}
USBD_StatusTypeDef USBD_LL_Transmit(USBD_HandleTypeDef *pdev, uint8_t addr,
                                    uint8_t *buffer, uint16_t size) {
    return HAL_PCD_EP_Transmit(pdev->pData, addr, buffer, size) == HAL_OK ? USBD_OK : USBD_FAIL;
}
USBD_StatusTypeDef USBD_LL_PrepareReceive(USBD_HandleTypeDef *pdev, uint8_t addr,
                                          uint8_t *buffer, uint16_t size) {
    return HAL_PCD_EP_Receive(pdev->pData, addr, buffer, size) == HAL_OK ? USBD_OK : USBD_FAIL;
}
uint32_t USBD_LL_GetRxDataSize(USBD_HandleTypeDef *pdev, uint8_t addr) {
    return HAL_PCD_EP_GetRxCount(pdev->pData, addr);
}
void USBD_LL_Delay(uint32_t delay) { HAL_Delay(delay); }

void *USBD_static_malloc(uint32_t size) {
    static uint32_t memory[160U];
    return size <= sizeof(memory) ? memory : NULL;
}
void USBD_static_free(void *p) { (void)p; }
