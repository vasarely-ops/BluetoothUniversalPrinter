package com.android.bluetoothuniversalprinter.printer.bluetooth;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Responsável por:
 *  - Redimensionar mantendo proporção (largura máxima em dots)
 *  - Convertar para 1bpp (PB)
 *  - Empacotar em listras (stripes) no formato GS v 0 (raster)
 *
 * Por que listras?
 *   -> Impressoras 58mm costumam ter buffer pequeno. Enviar a imagem inteira de uma vez
 *      pode resultar em corte no final. Em stripes, evitamos overflow.
 */
public class EscPosImageEncoder {

    public static final int MAX_WIDTH_DOTS_58 = 384;   // 58mm típico (48mm útil * 8 dots/mm)
    public static final int DEFAULT_STRIPE_HEIGHT = 96; // linhas por stripe (seguro entre 64~128)

    /** Redimensiona a imagem para maxWidthDots mantendo aspect ratio. */
    public static Bitmap scaleToMaxWidth(Bitmap src, int maxWidthDots) {
        if (src.getWidth() <= maxWidthDots) return ensureArgb(src);
        float ratio = (float) maxWidthDots / (float) src.getWidth();
        int newH = Math.round(src.getHeight() * ratio);
        Bitmap scaled = Bitmap.createScaledBitmap(src, maxWidthDots, newH, true);
        return ensureArgb(scaled);
    }

    /** Garante ARGB_8888 para leitura de pixels rápida. */
    public static Bitmap ensureArgb(Bitmap bmp) {
        if (bmp.getConfig() == Bitmap.Config.ARGB_8888) return bmp;
        return bmp.copy(Bitmap.Config.ARGB_8888, false);
    }

    /** Converte para PB com limiar fixo (rápido e suficiente para comprovantes). */
    public static Bitmap toMono(Bitmap src, int threshold) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int c = src.getPixel(x, y);
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                int lumin = (r + g + b) / 3;
                out.setPixel(x, y, lumin < threshold ? Color.BLACK : Color.WHITE);
            }
        }
        return out;
    }

    /**
     * Empacota um “stripe” (faixa horizontal) em bytes raster ESC/POS (1 = preto).
     * @param mono PB (preto/branco)
     * @param yStart linha inicial do stripe
     * @param stripeHeight altura do stripe
     * @return bytes prontos para mandar após o header GS v 0
     */
    public static byte[] packStripe(Bitmap mono, int yStart, int stripeHeight) {
        int width = mono.getWidth();
        int height = mono.getHeight();
        int h = Math.min(stripeHeight, height - yStart);

        int bytesPerRow = (width + 7) / 8;
        byte[] data = new byte[bytesPerRow * h];

        int idx = 0;
        for (int y = 0; y < h; y++) {
            int bitPos = 0;
            byte current = 0;
            for (int x = 0; x < width; x++) {
                int pixel = mono.getPixel(x, yStart + y);
                boolean isBlack = (pixel & 0x00FFFFFF) == 0x000000; // Color.BLACK
                current <<= 1;
                if (isBlack) current |= 0x01;
                bitPos++;
                if (bitPos == 8) {
                    data[idx++] = current;
                    bitPos = 0;
                    current = 0;
                }
            }
            if (bitPos != 0) {
                current <<= (8 - bitPos);
                data[idx++] = current;
            }
        }
        return data;
    }
}
