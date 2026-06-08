package com.softropic.skillars.infrastructure.logging;


import com.softropic.skillars.infrastructure.exception.ErrorCode;

public enum InventoryCode implements ErrorCode {
    INV_1001, //release count more than reserve count
    INV_1002, //no reservations to be released
    INV_1003,  //"The InventoryLog cannot be processed because there is no inventory left
    INV_1000,  //no corresponding inventory exists
    INV_SALE_COUNT_TOO_HIGH,
    INV_1004,  //attempt to refund more than what was purchased
    INV_1005,  //attempt to refund more than what was purchased or to refund when nothing was purchased
    INV_SALE_NOT_FULLY_PROCESSED,
    INV_ORDER_ITEM_EVENT_HANDLING_FAILED,
    INV_ORDER_EVENT_HANDLING_FAILED,
    INV_REVERT_FAILED,
    INV_INC_STOCK_FAILED,   //Attempt to incement Stock failed
    ;

    @Override
    public String getErrorCode() {
        return this.name();
    }
}
