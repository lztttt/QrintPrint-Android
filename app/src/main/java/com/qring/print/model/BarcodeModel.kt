package com.qring.print.model

/**
 * 条码类型定义。
 */
enum class CodeCategory {
    ONE_D,
    TWO_D
}

data class CodeType(
    val label: String,
    val category: CodeCategory,
    val scanType: Int  // ZXing 的 BarcodeFormat 枚举 ordinal 对应
)

/**
 * 支持的条码类型列表。
 */
val CODE_TYPES: List<CodeType> = listOf(
    CodeType("QR Code", CodeCategory.TWO_D, 256),    // QR_CODE
    CodeType("Data Matrix", CodeCategory.TWO_D, 257),  // DATA_MATRIX
    CodeType("Aztec", CodeCategory.TWO_D, 258),        // AZTEC
    CodeType("PDF417", CodeCategory.TWO_D, 259),       // PDF_417
    CodeType("Code 128", CodeCategory.ONE_D, 64),      // CODE_128
    CodeType("Code 39", CodeCategory.ONE_D, 32),       // CODE_39
    CodeType("Code 93", CodeCategory.ONE_D, 16),       // CODE_93
    CodeType("EAN-13", CodeCategory.ONE_D, 1),         // EAN_13
    CodeType("EAN-8", CodeCategory.ONE_D, 2),          // EAN_8
    CodeType("UPC-A", CodeCategory.ONE_D, 8),          // UPC_A
    CodeType("ITF", CodeCategory.ONE_D, 128)           // ITF (Interleaved 2 of 5)
)
