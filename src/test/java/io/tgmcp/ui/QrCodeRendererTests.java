package io.tgmcp.ui;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user-reported bug this exists to fix: the QR login link was previously shown only as raw
 * text, which no phone camera can scan. Confirms an actual, decodable PNG image comes back.
 */
class QrCodeRendererTests {

    private final QrCodeRenderer renderer = new QrCodeRenderer();

    @Test
    void rendersTheLinkAsADecodablePngDataUri() {
        String dataUri = this.renderer.toDataUri("tg://login?token=abc123");

        assertThat(dataUri).startsWith("data:image/png;base64,");
        byte[] png = Base64.getDecoder().decode(dataUri.substring("data:image/png;base64,".length()));
        // PNG file signature: 0x89 'P' 'N' 'G' \r \n 0x1A \n
        byte[] pngSignature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        assertThat(java.util.Arrays.copyOf(png, pngSignature.length)).isEqualTo(pngSignature);
    }

    @Test
    void differentLinksProduceDifferentImages() {
        String first = this.renderer.toDataUri("tg://login?token=aaa");
        String second = this.renderer.toDataUri("tg://login?token=bbb");

        assertThat(first).isNotEqualTo(second);
    }
}
