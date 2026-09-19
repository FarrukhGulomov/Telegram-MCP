package io.tgmcp.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.springframework.stereotype.Component;

/**
 * Renders a Telegram QR login link as an actual scannable image instead of showing the raw
 * link as plain text, which a phone camera cannot scan at all. Generated server-side, as a
 * {@code data:} URI the template drops straight into an {@code <img>} tag -- no external
 * QR-rendering script is needed, matching this app's self-hosted-only CSP.
 */
@Component
public class QrCodeRenderer {

    private static final int SIZE_PX = 280;

    public String toDataUri(String link) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, SIZE_PX, SIZE_PX, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        }
        catch (WriterException | IOException ex) {
            throw new IllegalStateException("Failed to render QR code image", ex);
        }
    }
}
