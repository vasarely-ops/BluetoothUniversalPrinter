package com.android.bluetoothuniversalprinter.printer.bluetooth;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.Map;

/**
 * Helper ESC/POS para impressoras térmicas Bluetooth / PAX.
 *
 * - Texto continua indo como comandos ESC/POS (alinhamento, bold, etc).
 * - Imagens, QRCode e Code128 são convertidos para bitmap 1bpp
 *   e enviados em modo raster (GS v 0), em FATIAS pequenas,
 *   para não estourar o buffer interno da impressora (erro unknown -2).
 *
 * Uso esperado NO MAIN:
 *
 *      printerHelper.reset();          // limpa formatação anterior
 *      printerHelper.setAlign(1);      // 0=esq,1=centro,2=dir
 *      printerHelper.printRasterBitmap(meuBitmap);
 *      printerHelper.feed(4);          // puxa papel
 *
 * IMPORTANTE:
 *  - printRasterBitmap NÃO chama reset() nem feed() sozinho.
 *  - A imagem é automaticamente redimensionada para LARGURA MÁXIMA 384px (58mm)
 *    e altura proporcional.
 *  - A imagem é enviada em stripes de até STRIPE_HEIGHT linhas
 *    (tiras horizontais), evitando overflow de buffer.
 */
public class BluetoothPrinterHelper {

    // Largura máxima típica de impressora 58mm em pixels
    private static final int MAX_PRINTER_WIDTH_PX = 384;

    // Altura (em linhas/pixels) de cada fatia enviada por GS v 0.
    // Quanto menor, menos chance de "buffer overflow".
    private static final int STRIPE_HEIGHT = 128;

    private final OutputStream out;

    // CP437 geralmente funciona bem para caracteres básicos.
    // Se acentuação sair errada, testar "ISO-8859-1" ou "GBK".
    private final Charset charset = Charset.forName("CP437");

    public BluetoothPrinterHelper(OutputStream out) {
        this.out = out;
    }

    /** ============================================================
     *  PRIMITIVAS DE ENVIO CRU / TEXTO
     *  ============================================================
     **/

    /** Envia bytes crus para a impressora e faz flush. */
    private void writeRaw(byte[] data) throws IOException {
        out.write(data);
        out.flush();
        // Se quiser debugar: logar hexdump aqui (cuidado para não travar UI)
    }

    /** Converte string para bytes na charset atual e envia. */
    private void writeText(String text) throws IOException {
        out.write(text.getBytes(charset));
        out.flush();
    }

    /** ============================================================
     *  COMANDOS ESC/POS BÁSICOS
     *  ============================================================
     **/

    /** ESC @ -> Reset de estado da impressora (limpa formatação atual). */
    public void reset() throws IOException {
        writeRaw(new byte[]{0x1B, 0x40});
    }

    /** Alimenta papel N linhas (Line Feed). */
    public void feed(int lines) throws IOException {
        for (int i = 0; i < lines; i++) {
            writeRaw(new byte[]{0x0A});
        }
    }

    /** ESC a n -> alinhamento (0 = esquerda, 1 = centro, 2 = direita). */
    public void setAlign(int align) throws IOException {
        writeRaw(new byte[]{0x1B, 0x61, (byte) align});
    }

    /** GS ! n -> tamanho da fonte (escala largura/altura). */
    public void setTextSize(byte n) throws IOException {
        // 0x00 normal, 0x11 2x, 0x22 3x (se suportado)
        writeRaw(new byte[]{0x1D, 0x21, n});
    }

    /** ESC E n -> negrito on/off. */
    public void setBold(boolean on) throws IOException {
        writeRaw(new byte[]{0x1B, 0x45, (byte) (on ? 1 : 0)});
    }

    /** GS V 1 -> corte parcial (algumas impressoras podem ignorar). */
    public void partialCut() throws IOException {
        writeRaw(new byte[]{0x1D, 0x56, 0x01});
    }

    /** ============================================================
     *  EXEMPLOS DE TEXTO (MESMO COMPORTAMENTO QUE VOCÊ JÁ TINHA)
     *  ============================================================
     **/

    /** Demonstra alinhamentos diferentes. */
    public void demoAlign() throws IOException {
        reset();

        setAlign(0);
        setTextSize((byte) 0x00);
        writeText("Alinhado à ESQUERDA\n");

        setAlign(1);
        writeText("Alinhado ao CENTRO\n");

        setAlign(2);
        writeText("Alinhado à DIREITA\n");

        feed(2);
        reset();
    }

    /** Demonstra tamanhos de fonte e bold. */
    public void demoTextSizes() throws IOException {
        reset();
        setAlign(0);

        setBold(false);
        setTextSize((byte) 0x00);
        writeText("Texto tamanho normal (0x00)\n");

        setBold(true);
        setTextSize((byte) 0x11);
        writeText("Texto GRANDE 2x (0x11)\n");

        setBold(false);
        setTextSize((byte) 0x22);
        writeText("Texto ENORME 3x (0x22)\n");

        setTextSize((byte) 0x00);
        feed(2);
        reset();
    }

    /** ============================================================
     * IMPRESSÃO DE IMAGEM EM MODO RASTER (GS v 0)
     *
     * Em vez de mandar a imagem inteira de uma vez (pode estourar
     * o buffer interno da PAX e gerar "unknown -2"), dividimos
     * a imagem em faixas horizontais (stripes) pequenas.
     *
     * Fluxo:
     *  1. Escalar imagem para largura MAX_PRINTER_WIDTH_PX (ex.: 384px).
     *     Altura fica proporcional.
     *  2. Converter para P&B (threshold fixo).
     *  3. Fatiar verticalmente em blocos de STRIPE_HEIGHT linhas.
     *  4. Para cada faixa, enviar comando ESC/POS raster:
     *
     *     GS v 0 m xL xH yL yH [data...]
     *
     *     - m = 0 (modo normal)
     *     - xL/xH = largura em bytes (largura_px arredondada /8)
     *     - yL/yH = altura da faixa, em linhas de pontos
     *     - data  = bitmap 1bpp, MSB = pixel mais à esquerda
     *
     * IMPORTANTE:
     *  - Este método NÃO chama reset(), setAlign() nem feed() sozinho.
     *    Isso é feito externamente no MainActivity antes/depois da impressão.
     * ============================================================
     **/
    public void printRasterBitmap(Bitmap src) throws IOException {

        // 1. escala + binarização (preto/branco)
        Bitmap bw = toMonochromeScaled(src, MAX_PRINTER_WIDTH_PX);

        final int widthPx = bw.getWidth();
        final int heightPx = bw.getHeight();

        // largura convertida para bytes por linha: 8 pixels -> 1 byte
        final int bytesPerRow = (widthPx + 7) / 8;

        int y = 0;
        while (y < heightPx) {
            // define altura da faixa atual
            int stripeH = Math.min(STRIPE_HEIGHT, heightPx - y);

            // monta os bytes 1bpp dessa faixa
            byte[] stripeData = bitmapStripeTo1Bpp(bw, y, stripeH, widthPx, bytesPerRow);

            // ---- Cabeçalho ESC/POS Raster para ESTA faixa ----
            // GS v 0 m xL xH yL yH
            byte m = 0x00;
            byte xL = (byte) (bytesPerRow & 0xFF);
            byte xH = (byte) ((bytesPerRow >> 8) & 0xFF);
            byte yL = (byte) (stripeH & 0xFF);
            byte yH = (byte) ((stripeH >> 8) & 0xFF);

            writeRaw(new byte[]{
                    0x1D, 0x76, 0x30, m,
                    xL, xH, yL, yH
            });

            // dados binários da faixa
            writeRaw(stripeData);

            // avança para próxima faixa
            y += stripeH;
        }
    }

    /**
     * Converte uma faixa horizontal (stripe) do bitmap P&B para 1bpp raster ESC/POS.
     *
     * bw           -> bitmap já P&B (preto/branco), ARGB_8888
     * startY       -> linha inicial da faixa
     * stripeH      -> altura da faixa em linhas
     * widthPx      -> largura total em pixels
     * bytesPerRow  -> quantos bytes por linha depois de empacotar 8px/byte
     *
     * Retorna um array onde cada linha da faixa vira bytesPerRow bytes.
     * bit mais significativo (MSB) de cada byte = pixel mais à esquerda.
     * bit=1 = ponto preto (marca térmica).
     */
    private byte[] bitmapStripeTo1Bpp(
            Bitmap bw,
            int startY,
            int stripeH,
            int widthPx,
            int bytesPerRow
    ) {

        byte[] outBytes = new byte[bytesPerRow * stripeH];
        int outIndex = 0;

        for (int row = 0; row < stripeH; row++) {
            int bitPos = 0;
            byte currentByte = 0;

            int y = startY + row;

            for (int x = 0; x < widthPx; x++) {
                int pixel = bw.getPixel(x, y);

                // pixel já está preto ou branco
                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                int lumin = (r + g + b) / 3;

                // ESC/POS raster: bit=1 -> ponto PRETO
                currentByte <<= 1;
                if (lumin < 128) {
                    currentByte |= 0x01;
                }
                bitPos++;

                // fechou 8 bits?
                if (bitPos == 8) {
                    outBytes[outIndex++] = currentByte;
                    bitPos = 0;
                    currentByte = 0;
                }
            }

            // sobras se largura não múltipla de 8
            if (bitPos != 0) {
                currentByte <<= (8 - bitPos);
                outBytes[outIndex++] = currentByte;
            }
        }

        return outBytes;
    }

    /**
     * Redimensiona o bitmap de entrada para caber na largura máxima da impressora
     * (por exemplo 384px para bobina 58mm), mantendo a proporção.
     *
     * Depois converte o resultado para ARGB_8888 e aplica um threshold fixo
     * para gerar uma imagem apenas PRETO / BRANCO (sem escala de cinza).
     *
     * maxWidthPx = 384 em impressoras 58mm comuns.
     */
    private Bitmap toMonochromeScaled(Bitmap original, int maxWidthPx) {

        Bitmap scaled = original;

        // 1. Reduz largura se necessário, mantendo proporção
        if (original.getWidth() > maxWidthPx) {
            float ratio = (float) maxWidthPx / (float) original.getWidth();
            int newH = Math.round(original.getHeight() * ratio);
            scaled = Bitmap.createScaledBitmap(original, maxWidthPx, newH, true);
        }

        // 2. Garante formato ARGB_8888
        if (scaled.getConfig() != Bitmap.Config.ARGB_8888) {
            scaled = scaled.copy(Bitmap.Config.ARGB_8888, false);
        }

        // 3. Converte para P&B (threshold 128)
        Bitmap bw = Bitmap.createBitmap(
                scaled.getWidth(),
                scaled.getHeight(),
                Bitmap.Config.ARGB_8888
        );

        for (int y = 0; y < scaled.getHeight(); y++) {
            for (int x = 0; x < scaled.getWidth(); x++) {
                int c = scaled.getPixel(x, y);
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                int lumin = (r + g + b) / 3;

                int v = (lumin < 128) ? Color.BLACK : Color.WHITE;
                bw.setPixel(x, y, v);
            }
        }
        return bw;
    }

    /** ============================================================
     * IMPRESSÃO DE IMAGEM A PARTIR DE DRAWABLE
     *
     * Atenção: este método apenas carrega o drawable como Bitmap e
     * chama printRasterBitmap(). Ele TAMBÉM não chama reset(), align
     * nem feed(). Quem chama (MainActivity) faz isso antes/depois.
     * ============================================================
     **/
    public void printImageResource(Resources res, int drawableId) throws IOException {
        Bitmap bmp = android.graphics.BitmapFactory.decodeResource(res, drawableId);
        if (bmp == null) {
            Log.e("PRINTER", "printImageResource: bitmap nulo do drawableId=" + drawableId);
            return;
        }
        printRasterBitmap(bmp);
    }

    /** ============================================================
     * QR CODE
     * Gera um Bitmap quadrado usando ZXing e imprime via raster.
     *
     * Uso típico no MainActivity:
     *
     *      printerHelper.reset();
     *      printerHelper.setAlign(1);
     *      printerHelper.printQrCode("meu-pix...", 256);
     *      printerHelper.feed(4);
     * ============================================================
     **/
    public void printQrCode(String data, int sizePx) throws IOException {
        Bitmap qr = generateQrBitmap(data, sizePx);
        if (qr == null) {
            Log.e("PRINTER", "printQrCode: falha ao gerar QR");
            return;
        }
        printRasterBitmap(qr);
    }

    private Bitmap generateQrBitmap(String data, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1); // margem pequena

            BitMatrix matrix = new MultiFormatWriter().encode(
                    data,
                    BarcodeFormat.QR_CODE,
                    sizePx,
                    sizePx,
                    hints
            );

            int w = matrix.getWidth();
            int h = matrix.getHeight();
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

            return bmp;

        } catch (WriterException e) {
            Log.e("PRINTER", "generateQrBitmap WriterException", e);
            return null;
        }
    }

    /** ============================================================
     * CODE128
     * Gera um código de barras CODE_128 via ZXing e imprime via raster.
     *
     * Uso típico no MainActivity:
     *
     *      printerHelper.reset();
     *      printerHelper.setAlign(1);
     *      printerHelper.printCode128("123456789012", 300, 100);
     *      printerHelper.feed(4);
     * ============================================================
     **/
    public void printCode128(String data, int widthPx, int heightPx) throws IOException {
        Bitmap code = generateCode128Bitmap(data, widthPx, heightPx);
        if (code == null) {
            Log.e("PRINTER", "printCode128: falha ao gerar CODE128");
            return;
        }
        printRasterBitmap(code);
    }

    private Bitmap generateCode128Bitmap(String data, int widthPx, int heightPx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix matrix = new MultiFormatWriter().encode(
                    data,
                    BarcodeFormat.CODE_128,
                    widthPx,
                    heightPx,
                    hints
            );

            int w = matrix.getWidth();
            int h = matrix.getHeight();
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;

        } catch (WriterException e) {
            Log.e("PRINTER", "generateCode128Bitmap WriterException", e);
            return null;
        }
    }



}
