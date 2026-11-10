package com.controller.btbridge

object HidProfile {
    // Descriptor nhị phân kết hợp Gamepad (ID 1) và Chuột + Cuộn (ID 2)
    val COMPOSITE_DESCRIPTOR = byteArrayOf(
        // ==================== GAMEPAD (REPORT ID 1) ====================
        0x05.toByte(), 0x01.toByte(),        // USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x05.toByte(),        // USAGE (Gamepad)
        0xa1.toByte(), 0x01.toByte(),        // COLLECTION (Application)
        0x85.toByte(), 0x01.toByte(),        //   REPORT_ID (1)
        
        // 16 Nút bấm (ABXY, Dpad, L1/R1, L2/R2 digital, Start/Select...)
        0x05.toByte(), 0x09.toByte(),        //   USAGE_PAGE (Button)
        0x19.toByte(), 0x01.toByte(),        //   USAGE_MINIMUM (Button 1)
        0x29.toByte(), 0x10.toByte(),        //   USAGE_MAXIMUM (Button 16)
        0x15.toByte(), 0x00.toByte(),        //   LOGICAL_MINIMUM (0)
        0x25.toByte(), 0x01.toByte(),        //   LOGICAL_MAXIMUM (1)
        0x75.toByte(), 0x01.toByte(),        //   REPORT_SIZE (1 bit)
        0x95.toByte(), 0x10.toByte(),        //   REPORT_COUNT (16 buttons)
        0x81.toByte(), 0x02.toByte(),        //   INPUT (Data,Var,Abs)
        
        // 4 trục Analog (-127 đến 127): LX, LY, RX, RY
        0x05.toByte(), 0x01.toByte(),        //   USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x30.toByte(),        //   USAGE (X)
        0x09.toByte(), 0x31.toByte(),        //   USAGE (Y)
        0x09.toByte(), 0x32.toByte(),        //   USAGE (Z)
        0x09.toByte(), 0x35.toByte(),        //   USAGE (Rz)
        0x15.toByte(), 0x81.toByte(),        //   LOGICAL_MINIMUM (-127)
        0x25.toByte(), 0x7f.toByte(),        //   LOGICAL_MAXIMUM (127)
        0x75.toByte(), 0x08.toByte(),        //   REPORT_SIZE (8 bits)
        0x95.toByte(), 0x04.toByte(),        //   REPORT_COUNT (4 axes)
        0x81.toByte(), 0x02.toByte(),        //   INPUT (Data,Var,Abs)
        0xc0.toByte(),                       // END_COLLECTION

        // ==================== MOUSE + WHEEL (REPORT ID 2) ====================
        0x05.toByte(), 0x01.toByte(),        // USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x02.toByte(),        // USAGE (Mouse)
        0xa1.toByte(), 0x01.toByte(),        // COLLECTION (Application)
        0x85.toByte(), 0x02.toByte(),        //   REPORT_ID (2)
        0x09.toByte(), 0x01.toByte(),        //   USAGE (Pointer)
        0xa1.toByte(), 0x00.toByte(),        //   COLLECTION (Physical)
        
        // Nút chuột (Trái, Phải, Giữa) + 5 bits padding
        0x05.toByte(), 0x09.toByte(),        //     USAGE_PAGE (Button)
        0x19.toByte(), 0x01.toByte(),        //     USAGE_MINIMUM (Button 1)
        0x29.toByte(), 0x03.toByte(),        //     USAGE_MAXIMUM (Button 3)
        0x15.toByte(), 0x00.toByte(),        //     LOGICAL_MINIMUM (0)
        0x25.toByte(), 0x01.toByte(),        //     LOGICAL_MAXIMUM (1)
        0x75.toByte(), 0x01.toByte(),        //     REPORT_SIZE (1 bit)
        0x95.toByte(), 0x03.toByte(),        //     REPORT_COUNT (3 buttons)
        0x81.toByte(), 0x02.toByte(),        //     INPUT (Data,Var,Abs)
        0x75.toByte(), 0x05.toByte(),        //     REPORT_SIZE (5 bits padding)
        0x95.toByte(), 0x01.toByte(),        //     REPORT_COUNT (1)
        0x81.toByte(), 0x03.toByte(),        //     INPUT (Cnst,Var,Abs)
        
        // Độ dịch chuyển chuột dX, dY và con lăn Wheel
        0x05.toByte(), 0x01.toByte(),        //     USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x30.toByte(),        //     USAGE (X)
        0x09.toByte(), 0x31.toByte(),        //     USAGE (Y)
        0x09.toByte(), 0x38.toByte(),        //     USAGE (Wheel)
        0x15.toByte(), 0x81.toByte(),        //     LOGICAL_MINIMUM (-127)
        0x25.toByte(), 0x7f.toByte(),        //     LOGICAL_MAXIMUM (127)
        0x75.toByte(), 0x08.toByte(),        //     REPORT_SIZE (8 bits)
        0x95.toByte(), 0x03.toByte(),        //     REPORT_COUNT (3 axes: X, Y, Wheel)
        0x81.toByte(), 0x06.toByte(),        //     INPUT (Data,Var,Rel)
        
        0xc0.toByte(),                       //   END_COLLECTION (Physical)
        0xc0.toByte()                        // END_COLLECTION (Application)
    )
}
