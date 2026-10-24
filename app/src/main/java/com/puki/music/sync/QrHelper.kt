package com.puki.music.sync

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

object QrHelper {

    fun generateQrBitmap(
        content: String,
        size: Int = 512,
        foregroundColor: Int = Color.WHITE,
        backgroundColor: Int = Color.TRANSPARENT
    ): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.MARGIN, 1)
        }

        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) foregroundColor else backgroundColor
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun startQrScanner(
        activity: Activity,
        onSuccess: (String) -> Unit,
        onCanceled: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

        val scanner = GmsBarcodeScanning.getClient(activity, options)

        // Pre-check module install to ensure seamless scan without Play Services delay
        val moduleInstallClient = ModuleInstall.getClient(activity)
        val request = ModuleInstallRequest.newBuilder()
            .addApi(scanner)
            .build()

        moduleInstallClient.installModules(request)
            .addOnSuccessListener {
                scanner.startScan()
                    .addOnSuccessListener { barcode ->
                        val rawValue = barcode.rawValue
                        if (!rawValue.isNullOrBlank()) {
                            onSuccess(rawValue)
                        } else {
                            onFailure(IllegalStateException("Kode QR kosong"))
                        }
                    }
                    .addOnCanceledListener {
                        onCanceled()
                    }
                    .addOnFailureListener { e ->
                        onFailure(e)
                    }
            }
            .addOnFailureListener {
                // Fallback direct scan even if module install listener fails
                scanner.startScan()
                    .addOnSuccessListener { barcode ->
                        val rawValue = barcode.rawValue
                        if (!rawValue.isNullOrBlank()) {
                            onSuccess(rawValue)
                        } else {
                            onFailure(IllegalStateException("Kode QR kosong"))
                        }
                    }
                    .addOnCanceledListener {
                        onCanceled()
                    }
                    .addOnFailureListener { e ->
                        onFailure(e)
                    }
            }
    }
}
