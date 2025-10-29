package com.android.bluetoothuniversalprinter.printer.bluetooth;

import android.graphics.Bitmap;
import android.util.Log;

/**
 * Utilitários de imagem para impressão ESC/POS.
 *
 * Responsabilidades:
 * - scaleToPrinterWidth58mm: escala mantendo proporção para caber em 58mm (~384px úteis).
 * - toMono1BppData: converte o bitmap para array 1bpp (1=preto) linha a linha.
 * - sliceRaster: quebra a imagem em blocos horizontais menores (por ex. 200px de altura)
 *   para mandar em múltiplos comandos GS v 0 em vez de um monstro gigante.
 *
 * Essa abordagem evita estouro de buffer interno e o problema do "corta 20% no final".
 */
public class PrinterBitmapUtils {

    // Largura útil típica de bobina 58mm em dots. Muitas impressoras 58mm usam 384 dots.
    public static final int MAX_WIDTH_58MM_PX = 384;

    /**
     * Redimensiona o bitmap original para caber na largura da cabeça térmica (384 px),
     * mantendo a proporção da altura. Retorna sempre ARGB_8888.
     */
    public static Bitmap scaleToPrinterWidth58mm(Bitmap original) {
        if (original == null) return null;

        Bitmap scaled = original;
        if (original.getWidth() > MAX_WIDTH_58MM_PX) {
            float ratio = (float) MAX_WIDTH_58MM_PX / (float) original.getWidth();
            int newH = Math.round(original.getHeight() * ratio);
            scaled = Bitmap.createScaledBitmap(original, MAX_WIDTH_58MM_PX, newH, true);
        }

        if (scaled.getConfig() != Bitmap.Config.ARGB_8888) {
            scaled = scaled.copy(Bitmap.Config.ARGB_8888, false);
        }
        return scaled;
    }

    /**
     * Converte um bitmap ARGB_8888 já na largura final para:
     *  - bytesPerRow = ceil(width/8)
     *  - data 1bpp onde bit=1 é PRETO
     *
     * Retorna um objeto helper com largura, altura e o array pronto.
     */
    public static RasterData toMono1BppData(Bitmap bmp) {
        if (bmp == null) return null;

        int width = bmp.getWidth();
        int height = bmp.getHeight();

        int bytesPerRow = (width + 7) / 8;
        byte[] imageBytes = new byte[bytesPerRow * height];

        int idx = 0;
        for (int y = 0; y < height; y++) {
            int bitPos = 0;
            byte currentByte = 0;

            for (int x = 0; x < width; x++) {
                int pixel = bmp.getPixel(x, y);

                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                int lumin = (r + g + b) / 3;

                // bit=1 imprime ponto preto
                currentByte <<= 1;
                if (lumin < 128) {
                    currentByte |= 0x01;
                }

                bitPos++;

                if (bitPos == 8) {
                    imageBytes[idx++] = currentByte;
                    bitPos = 0;
                    currentByte = 0;
                }
            }

            if (bitPos != 0) {
                currentByte <<= (8 - bitPos);
                imageBytes[idx++] = currentByte;
            }
        }

        return new RasterData(width, height, bytesPerRow, imageBytes);
    }

    /**
     * Quebra o raster completo (toda a imagem) em fatias horizontais menores.
     *
     * Por quê? Algumas impressoras travam ou cortam o final se você manda
     * uma imagem MUITO alta em um único GS v 0. Fatiando em blocos menores
     * (por ex. 200 linhas), você manda vários GS v 0 seguidos.
     *
     * Isso evita buffer overflow interno e evita o corte de ~20% no final.
     *
     * @param full   RasterData completo da imagem já 1bpp
     * @param sliceHeightMaxPx altura máxima de cada fatia (por ex. 200)
     */
    public static RasterSlice[] sliceRaster(RasterData full, int sliceHeightMaxPx) {
        if (full == null) return null;
        if (sliceHeightMaxPx <= 0) sliceHeightMaxPx = 200;

        int totalH = full.heightPx;
        int bytesPerRow = full.bytesPerRow;
        int totalRowsSent = 0;

        int sliceCount = (int) Math.ceil((double) totalH / sliceHeightMaxPx);
        RasterSlice[] slices = new RasterSlice[sliceCount];

        for (int i = 0; i < sliceCount; i++) {
            int startRow = i * sliceHeightMaxPx;
            int thisHeight = Math.min(sliceHeightMaxPx, totalH - startRow);

            byte[] buf = new byte[thisHeight * bytesPerRow];

            // copia bloco correspondente dessa fatia
            int destPos = 0;
            for (int row = 0; row < thisHeight; row++) {
                int srcPos = (startRow + row) * bytesPerRow;
                System.arraycopy(full.data, srcPos, buf, destPos, bytesPerRow);
                destPos += bytesPerRow;
            }

            slices[i] = new RasterSlice(
                    full.widthPx,
                    thisHeight,
                    bytesPerRow,
                    buf
            );

            totalRowsSent += thisHeight;
        }

        Log.d("PRINTER", "sliceRaster: totalH=" + totalH
                + " fatias=" + sliceCount
                + " rowsSent=" + totalRowsSent);

        return slices;
    }

    /** Container simples com o raster 1bpp inteiro */
    public static class RasterData {
        public final int widthPx;
        public final int heightPx;
        public final int bytesPerRow;
        public final byte[] data;

        public RasterData(int w, int h, int bpr, byte[] d) {
            this.widthPx = w;
            this.heightPx = h;
            this.bytesPerRow = bpr;
            this.data = d;
        }
    }

    /** Cada fatia pronta pra mandar em um GS v 0 isolado */
    public static class RasterSlice {
        public final int widthPx;
        public final int heightPx;
        public final int bytesPerRow;
        public final byte[] data;

        public RasterSlice(int w, int h, int bpr, byte[] d) {
            this.widthPx = w;
            this.heightPx = h;
            this.bytesPerRow = bpr;
            this.data = d;
        }
    }
}
