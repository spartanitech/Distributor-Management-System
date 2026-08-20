package com.spartan.dms.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

@Component
public class QrCodeGenerator {

    public byte[] generateQrCode(String text,
                                 int width,
                                 int height) throws Exception {

        BitMatrix matrix = new MultiFormatWriter()
                .encode(text, BarcodeFormat.QR_CODE, width, height);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        MatrixToImageWriter.writeToStream(
                matrix,
                "PNG",
                outputStream
        );

        return outputStream.toByteArray();
    }
}