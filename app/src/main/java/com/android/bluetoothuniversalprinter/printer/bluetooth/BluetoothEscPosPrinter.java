package com.android.bluetoothuniversalprinter.printer.bluetooth;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Driver ESC/POS de alto nível para impressoras térmicas Bluetooth 58mm.
 *
 * Funcionalidades:
 *  - Texto (alinhamento, negrito, escala 1x/2x/3x)
 *  - Imagem bitmap (logo, comprovante renderizado etc.)
 *  - QR Code e Code128 via ZXing
 *  - Grids de bolinhas numeradas e caixas arredondadas numeradas
 *  - Parágrafo dentro de caixa arredondada
 *
 * Implementação segura:
 *  - Sempre envia imagens em faixas ("stripes") pequenas via GS v 0 (modo raster)
 *    → evita corte de ~20% do final da imagem e evita erro de buffer (-2)
 *  - NÃO existe recursão (evita StackOverflowError)
 *  - Ordem dos comandos ESC/POS no txtPrint foi ajustada para respeitar
 *    peculiaridades de algumas impressoras embarcadas (ex. POS PAX)
 */
public class BluetoothEscPosPrinter {

    // ---- Configuração física / protocolo ----

    /** Largura útil típica da cabeça térmica 58mm: ~384 pontos horizontais. */
    private static final int MAX_WIDTH_DOTS = 384;

    /** Altura máxima de cada "stripe" (faixa) de imagem enviada de uma vez. */
    private static final int STRIPE_HEIGHT = 64;

    /** Pausa curta entre stripes para não saturar buffer da impressora. */
    private static final int STRIPE_PAUSE_MS = 20;

    /** Charset para envio de texto simples. Ajuste se precisar de acentuação específica. */
    private static final Charset DEFAULT_CHARSET = Charset.forName("CP437");
    // Alternativas comuns: Charset.forName("ISO-8859-1"), "GBK" etc.

    private final OutputStream out;

    public BluetoothEscPosPrinter(OutputStream out) {
        this.out = out;
    }

    // ------------------------------------------------------------------------
    //  BAIXO NÍVEL ESC/POS
    // ------------------------------------------------------------------------

    /** Envia bytes puros e flush. */
    private void writeRaw(byte[] data) throws IOException {
        out.write(data);
        out.flush();
    }

    /** Envia texto codificado + flush. */
    private void writeText(String text) throws IOException {
        out.write(text.getBytes(DEFAULT_CHARSET));
        out.flush();
    }

    /** ESC @ : Reset da impressora (limpa estilos/estado interno). */
    public void reset() throws IOException {
        writeRaw(new byte[]{0x1B, 0x40});
    }

    /** Alimenta N linhas. */
    public void feed(int lines) throws IOException {
        for (int i = 0; i < lines; i++) {
            writeRaw(new byte[]{0x0A});
        }
    }

    /**
     * ESC a n : alinhamento
     *  n=0 -> esquerda
     *  n=1 -> centro
     *  n=2 -> direita
     */
    public void setAlign(int align) throws IOException {
        int safe = (align == 1 || align == 2) ? align : 0;
        writeRaw(new byte[]{0x1B, 0x61, (byte) safe});
    }

    /**
     * GS ! n : escala de fonte (largura x altura)
     *  0x00 -> normal
     *  0x11 -> 2x largura / 2x altura
     *  0x22 -> 3x largura / 3x altura (algumas impressoras só suportam até 2x. Se não suportarem,
     *          normalmente elas ignoram a parte extra e continuam imprimindo)
     */
    public void setTextSize(byte n) throws IOException {
        writeRaw(new byte[]{0x1D, 0x21, n});
    }

    /** ESC E n : negrito on/off (n=1 on, n=0 off). */
    public void setBold(boolean on) throws IOException {
        writeRaw(new byte[]{0x1B, 0x45, (byte) (on ? 1 : 0)});
    }

    /**
     * GS V 1 : corte parcial (quando houver guilhotina).
     * Muitas 58mm baratas e muitas PAX simplesmente ignoram isso. Não é erro.
     */
    public void partialCut() throws IOException {
        writeRaw(new byte[]{0x1D, 0x56, 0x01});
    }

    /** ------------------------------------------------------------------------
    *   CICLO DE IMPRESSÃO (opcional, só pra organizar cupom/bloco)
    *   ------------------------------------------------------------------------
     */

    /**
     * Chame no início de um "bloco de impressão".
     * Garante estado conhecido (reset, alinhamento esq, texto normal sem bold).
     */
    public void beginJob() throws IOException {
        reset();
        setBold(false);
        setTextSize((byte) 0x00);
        setAlign(0);
    }

    /**
     * Chame ao finalizar o "bloco de impressão".
     * Dá um pequeno feed e volta estilo pro neutro.
     * Não chama corte automático; corte é opcional com partialCut().
     */
    public void endJob() throws IOException {
        feed(2);
        setBold(false);
        setTextSize((byte) 0x00);
        setAlign(0);
    }

    // ------------------------------------------------------------------------
    //  TEXTO
    // ------------------------------------------------------------------------

    /**
     * Imprime UMA linha de texto formatada (com quebra de linha automática após ela).
     *
     * A ordem dos comandos ESC/POS é importante para algumas impressoras embutidas:
     *  1) alinhamento
     *  2) escala da fonte (GS ! n)
     *  3) negrito (ESC E n)
     *  4) texto
     *
     * @param text  Conteúdo a imprimir (sem \n no final, nós vamos inserir)
     * @param align 0 = esquerda, 1 = centro, 2 = direita
     * @param scale 0 = normal, 1 = 2x (0x11), 3 = 3x (0x22, se suportado)
     */
    public void txtPrint(String text, int align, int scale) throws IOException {
        // 1) alinhamento
        setAlign(align);

        // 2) tamanho
        byte sizeByte = mapScaleToEscPosByte(scale);
        setTextSize(sizeByte);

        // 3) bold automático se escala > 0
        setBold(scale > 0);

        // 4) texto + newline
        writeText(text);
        writeText("\n");
    }

    /** Traduz nossa escala "amigável" para o byte GS ! n esperado pela impressora. */
    private byte mapScaleToEscPosByte(int scale) throws IOException {
        switch (scale) {
            case 1:
                return 0x11; // 2x largura/altura
            case 3:
                return 0x22; // 3x largura/altura (se suportar)
            case 0:
            default:
                return 0x00; // normal
        }
    }

    // ------------------------------------------------------------------------
    //  IMPRESSÃO DE IMAGEM / QR / CODE128
    // ------------------------------------------------------------------------

    /**
     * Carrega um drawable (ex: logo da sua empresa) e envia como imagem raster.
     * A imagem é automaticamente:
     *  - redimensionada para caber na boca (MAX_WIDTH_DOTS, normalmente 384 px)
     *  - convertida para PB (preto e branco)
     *  - fatiada e enviada em stripes pequenas para não estourar buffer
     */
    public void printImageResource(Resources res, int drawableId) throws IOException {
        Bitmap bmp = android.graphics.BitmapFactory.decodeResource(res, drawableId);
        if (bmp == null) {
            Log.e("PRINTER", "printImageResource: bitmap nulo id=" + drawableId);
            return;
        }
        setAlign(1); // centraliza imagem antes de mandar
        printBitmapAsRasterStripes(bmp);
    }

    /**
     * Gera um QR Code com ZXing e imprime como imagem raster segura.
     */
    public void printQrCode(String data, int sizePx) throws IOException {
        Bitmap qr = generateQrBitmap(data, sizePx);
        if (qr == null) {
            Log.e("PRINTER", "printQrCode: falha ao gerar QR");
            return;
        }
        setAlign(1);
        printBitmapAsRasterStripes(qr);
    }

    /**
     * Gera um CODE128 com ZXing e imprime como imagem raster segura.
     */
    public void printCode128(String data, int widthPx, int heightPx) throws IOException {
        Bitmap code = generateCode128Bitmap(data, widthPx, heightPx);
        if (code == null) {
            Log.e("PRINTER", "printCode128: falha ao gerar CODE128");
            return;
        }
        setAlign(1);
        printBitmapAsRasterStripes(code);
    }

    /**
     * Função principal de envio de bitmap para a impressora:
     *
     * 1. Escala o bitmap para caber na largura MAX_WIDTH_DOTS mantendo proporção.
     * 2. Converte para preto/branco (threshold simples) e garante ARGB_8888.
     * 3. Divide em faixas horizontais (STRIPE_HEIGHT) para não encher o buffer da impressora.
     * 4. Para cada faixa, monta GS v 0 (modo raster) e envia.
     *
     * Esse fluxo evita:
     *  - imagem "cortada pela metade"
     *  - erro interno "unknown -2" / travamento por excesso de dados
     *  - StackOverflowError (não existe recursão aqui)
     */
    public void printBitmapAsRasterStripes(Bitmap src) throws IOException {
        // 1) escala pra largura máxima e converte PB
        Bitmap mono = toMonoScaled(src, MAX_WIDTH_DOTS);

        int width = mono.getWidth();
        int height = mono.getHeight();
        int bytesPerRow = (width + 7) / 8;

        // 2) envia stripe por stripe
        for (int yStart = 0; yStart < height; yStart += STRIPE_HEIGHT) {
            int stripeH = Math.min(STRIPE_HEIGHT, height - yStart);

            // empacota só essa faixa em 1bpp raster ESC/POS
            byte[] stripeBytes = packStripe(mono, yStart, stripeH);

            // cabeçalho GS v 0
            byte xL = (byte) (bytesPerRow & 0xFF);
            byte xH = (byte) ((bytesPerRow >> 8) & 0xFF);
            byte yL = (byte) (stripeH & 0xFF);
            byte yH = (byte) ((stripeH >> 8) & 0xFF);

            // GS v 0 m xL xH yL yH   (m=0 modo normal)
            writeRaw(new byte[]{
                    0x1D, 0x76, 0x30, 0x00,
                    xL, xH, yL, yH
            });

            // corpo do stripe
            writeRaw(stripeBytes);

            // pausa leve pra não sobrecarregar buffer físico da impressora
            try {
                Thread.sleep(STRIPE_PAUSE_MS);
            } catch (InterruptedException ignored) {}
        }

        // alimenta 1 linha depois da imagem
        feed(1);
    }

    /**
     * Escala a imagem para no máximo maxWidth pixels de largura,
     * mantém proporção, força ARGB_8888, e gera bitmap PB puro (preto ou branco).
     */
    private Bitmap toMonoScaled(Bitmap src, int maxWidth) {
        // escala se necessário
        Bitmap scaled = src;
        if (src.getWidth() > maxWidth) {
            float ratio = (float) maxWidth / (float) src.getWidth();
            int newH = Math.round(src.getHeight() * ratio);
            scaled = Bitmap.createScaledBitmap(src, maxWidth, newH, true);
        }

        // garante formato ARGB_8888
        if (scaled.getConfig() != Bitmap.Config.ARGB_8888) {
            scaled = scaled.copy(Bitmap.Config.ARGB_8888, false);
        }

        // converte para preto/branco (threshold fixo)
        Bitmap mono = Bitmap.createBitmap(scaled.getWidth(), scaled.getHeight(), Bitmap.Config.ARGB_8888);

        for (int y = 0; y < scaled.getHeight(); y++) {
            for (int x = 0; x < scaled.getWidth(); x++) {
                int c = scaled.getPixel(x, y);
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = (c) & 0xFF;
                int lumin = (r + g + b) / 3;
                mono.setPixel(x, y, lumin < 128 ? Color.BLACK : Color.WHITE);
            }
        }

        return mono;
    }

    /**
     * Empacota uma faixa horizontal (stripe) do bitmap PB em bytes ESC/POS raster.
     * Cada byte representa 8 pixels (bit mais significativo primeiro).
     * bit=1 -> ponto preto / imprime.
     */
    private byte[] packStripe(Bitmap mono, int yStart, int stripeH) {
        int width = mono.getWidth();
        int bytesPerRow = (width + 7) / 8;
        byte[] data = new byte[bytesPerRow * stripeH];
        int idx = 0;

        for (int row = 0; row < stripeH; row++) {
            int y = yStart + row;
            int bitPos = 0;
            byte currentByte = 0;

            for (int x = 0; x < width; x++) {
                int pixel = mono.getPixel(x, y);
                boolean isBlack = (pixel & 0x00FFFFFF) == 0x000000; // preto?

                currentByte <<= 1;
                if (isBlack) {
                    currentByte |= 0x01;
                }

                bitPos++;
                if (bitPos == 8) {
                    data[idx++] = currentByte;
                    bitPos = 0;
                    currentByte = 0;
                }
            }

            // completa último byte se a largura não for múltipla de 8
            if (bitPos != 0) {
                currentByte <<= (8 - bitPos);
                data[idx++] = currentByte;
            }
        }

        return data;
    }

    // ------------------------------------------------------------------------
    //  QR CODE / CODE128 (ZXing)
    // ------------------------------------------------------------------------

    private Bitmap generateQrBitmap(String data, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix matrix = new MultiFormatWriter().encode(
                    data,
                    BarcodeFormat.QR_CODE,
                    sizePx,
                    sizePx,
                    hints
            );
            return bitMatrixToBitmap(matrix);

        } catch (WriterException e) {
            Log.e("PRINTER", "generateQrBitmap WriterException", e);
            return null;
        }
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
            return bitMatrixToBitmap(matrix);

        } catch (WriterException e) {
            Log.e("PRINTER", "generateCode128Bitmap WriterException", e);
            return null;
        }
    }

    /** Converte a matriz ZXing (preto/branco) em Bitmap ARGB_8888 PB. */
    private Bitmap bitMatrixToBitmap(BitMatrix matrix) {
        int w = matrix.getWidth();
        int h = matrix.getHeight();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bmp;
    }

    // ------------------------------------------------------------------------
    //  GRID DE NÚMEROS EM CÍRCULOS
    // ------------------------------------------------------------------------

    /**
     * Imprime uma grade de números dentro de círculos.
     *
     * Exemplo de uso:
     *   printer.printGrid(
     *       new String[]{"01","02","03","04","05","06","07","08","09","10"},
     *       5,     // colunas
     *       24,    // raio do círculo em px
     *       22f    // tamanho de fonte desejado em px
     *   );
     *
     * @param numbers        Lista de strings (ex: ["01","02","03",...])
     * @param columns        Nº de colunas por linha (>=1)
     * @param radiusPx       Raio do círculo em px (ex.: 24)
     * @param textSizePx     Tamanho de fonte desejado (px). Se não couber, reduzimos.
     */
    public void printGrid(List<String> numbers, int columns, int radiusPx, float textSizePx) throws IOException {
        if (numbers == null || numbers.isEmpty()) return;
        if (columns < 1) columns = 1;
        if (radiusPx < 6) radiusPx = 6;
        if (textSizePx < 6f) textSizePx = 6f;

        Bitmap grid = buildGridBitmap(numbers, columns, radiusPx, textSizePx);

        // Se ficou mais largo que o papel, reduz proporcionalmente
        if (grid.getWidth() > MAX_WIDTH_DOTS) {
            float scale = (float) MAX_WIDTH_DOTS / (float) grid.getWidth();
            int newW = MAX_WIDTH_DOTS;
            int newH = Math.max(1, Math.round(grid.getHeight() * scale));
            grid = Bitmap.createScaledBitmap(grid, newW, newH, true);
        }

        // Centraliza visualmente
        setAlign(1);
        printBitmapAsRasterStripes(grid);
    }

    /** Overload: array de String direto. */
    public void printGrid(String[] numbers, int columns, int radiusPx, float textSizePx) throws IOException {
        printGrid(Arrays.asList(numbers), columns, radiusPx, textSizePx);
    }

    /** Overload: array de int -> converte para "01", "02", ... */
    public void printGrid(int[] numbers, int columns, int radiusPx, float textSizePx) throws IOException {
        String[] s = new String[numbers.length];
        for (int i = 0; i < numbers.length; i++) {
            s[i] = String.format("%02d", numbers[i]);
        }
        printGrid(s, columns, radiusPx, textSizePx);
    }

    /**
     * Monta um bitmap com as bolinhas numeradas.
     * Cada célula = quadrado que contém 1 círculo e o número centralizado.
     */
    private Bitmap buildGridBitmap(List<String> numbers, int columns, int radiusPx, float wantedTextSizePx) {
        final int n = numbers.size();
        final int rows = (n + columns - 1) / columns;

        // padding interno entre borda da célula e o círculo
        final int pad = Math.max(4, radiusPx / 2);

        // tamanho total de cada célula (largura = altura):
        final int cellSize = radiusPx * 2 + pad * 2;

        final int bmpWidth = columns * cellSize;
        final int bmpHeight = rows * cellSize;

        Bitmap bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // fundo branco
        canvas.drawColor(Color.WHITE);

        // caneta para desenhar o círculo
        Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        circle.setStyle(Paint.Style.STROKE);
        circle.setColor(Color.BLACK);
        circle.setStrokeWidth(Math.max(2f, radiusPx / 8f));

        // caneta do texto
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        // vamos tentar o tamanho desejado e reduzir se não couber
        float adjustedTextSize = wantedTextSizePx;
        textPaint.setTextSize(adjustedTextSize);

        // avaliar se "88" cabe dentro do círculo.
        Rect bounds = new Rect();
        final float maxContentSpanPx = radiusPx * 1.6f; // ~80% do diâmetro

        while (true) {
            textPaint.setTextSize(adjustedTextSize);
            textPaint.getTextBounds("88", 0, 2, bounds);

            float w = bounds.width();
            float h = bounds.height();

            if (w <= maxContentSpanPx && h <= maxContentSpanPx) {
                break;
            }

            adjustedTextSize *= 0.9f;
            if (adjustedTextSize < 6f) {
                adjustedTextSize = 6f;
                textPaint.setTextSize(adjustedTextSize);
                break;
            }
        }

        // desenha cada célula
        for (int i = 0; i < n; i++) {
            int row = i / columns;
            int col = i % columns;

            int left = col * cellSize;
            int top = row * cellSize;

            float cx = left + cellSize / 2f;
            float cy = top + cellSize / 2f;

            // círculo
            canvas.drawCircle(cx, cy, radiusPx, circle);

            // texto
            String label = numbers.get(i);
            if (label == null) label = "";

            Rect tb = new Rect();
            textPaint.getTextBounds(label, 0, label.length(), tb);

            float textX = cx - (tb.width() / 2f) - tb.left;
            float textY = cy + (tb.height() / 2f) - tb.bottom;

            canvas.drawText(label, textX, textY, textPaint);
        }

        return bmp;
    }

    // ------------------------------------------------------------------------
    //  GRID DE NÚMEROS EM RETÂNGULOS COM CANTOS ARREDONDADOS
    // ------------------------------------------------------------------------

    /**
     * Imprime uma grade de caixinhas arredondadas com números dentro.
     *
     * @param numbers          Lista com os valores (ex: ["01","02","03",...])
     * @param columns          Nº de colunas
     * @param boxWidthPx       Largura de cada caixa em px
     * @param boxHeightPx      Altura de cada caixa em px
     * @param cornerRadiusPx   Raio dos cantos arredondados em px
     * @param textSizePxWanted Tamanho de fonte desejado em px (ajustamos pra caber)
     */
    public void printRoundedGrid(
            List<String> numbers,
            int columns,
            int boxWidthPx,
            int boxHeightPx,
            int cornerRadiusPx,
            float textSizePxWanted
    ) throws IOException {

        if (numbers == null || numbers.isEmpty()) return;
        if (columns < 1) columns = 1;
        if (boxWidthPx < 20) boxWidthPx = 20;
        if (boxHeightPx < 20) boxHeightPx = 20;
        if (cornerRadiusPx < 2) cornerRadiusPx = 2;
        if (textSizePxWanted < 6f) textSizePxWanted = 6f;

        Bitmap gridBmp = buildRoundedGridBitmap(
                numbers,
                columns,
                boxWidthPx,
                boxHeightPx,
                cornerRadiusPx,
                textSizePxWanted
        );

        // escala pra caber na boca se necessário
        if (gridBmp.getWidth() > MAX_WIDTH_DOTS) {
            float scale = (float) MAX_WIDTH_DOTS / (float) gridBmp.getWidth();
            int newW = MAX_WIDTH_DOTS;
            int newH = Math.max(1, Math.round(gridBmp.getHeight() * scale));
            gridBmp = Bitmap.createScaledBitmap(gridBmp, newW, newH, true);
        }

        // centraliza e manda
        setAlign(1);
        printBitmapAsRasterStripes(gridBmp);
    }

    /** Overload conveniente: array de String. */
    public void printRoundedGrid(
            String[] numbers,
            int columns,
            int boxWidthPx,
            int boxHeightPx,
            int cornerRadiusPx,
            float textSizePxWanted
    ) throws IOException {
        printRoundedGrid(
                Arrays.asList(numbers),
                columns,
                boxWidthPx,
                boxHeightPx,
                cornerRadiusPx,
                textSizePxWanted
        );
    }

    /** Overload conveniente: array de int → "01","02",... */
    public void printRoundedGrid(
            int[] numbers,
            int columns,
            int boxWidthPx,
            int boxHeightPx,
            int cornerRadiusPx,
            float textSizePxWanted
    ) throws IOException {

        String[] s = new String[numbers.length];
        for (int i = 0; i < numbers.length; i++) {
            s[i] = String.format("%02d", numbers[i]);
        }

        printRoundedGrid(
                Arrays.asList(s),
                columns,
                boxWidthPx,
                boxHeightPx,
                cornerRadiusPx,
                textSizePxWanted
        );
    }

    /**
     * Monta um bitmap com as caixinhas arredondadas e o texto centralizado.
     */
    private Bitmap buildRoundedGridBitmap(
            List<String> numbers,
            int columns,
            int boxWidthPx,
            int boxHeightPx,
            int cornerRadiusPx,
            float wantedTextSizePx
    ) {
        final int count = numbers.size();
        final int rows = (count + columns - 1) / columns;

        final int bmpW = columns * boxWidthPx;
        final int bmpH = rows * boxHeightPx;

        Bitmap bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // fundo branco
        canvas.drawColor(Color.WHITE);

        // pincel da borda arredondada
        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setColor(Color.BLACK);
        boxPaint.setStrokeWidth(2f);

        // pincel do texto
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        // começa com tamanho desejado e reduz até caber na caixa
        float adjustedSize = wantedTextSizePx;
        textPaint.setTextSize(adjustedSize);

        // vamos garantir que o texto "88" cabe dentro da caixa com um padding
        final float textPadding = 4f;
        final float maxTextWAllowed = boxWidthPx - 2f * textPadding;
        final float maxTextHAllowed = boxHeightPx - 2f * textPadding;

        Rect bounds = new Rect();
        while (true) {
            textPaint.setTextSize(adjustedSize);
            textPaint.getTextBounds("88", 0, 2, bounds);

            float w = bounds.width();
            float h = bounds.height();

            if (w <= maxTextWAllowed && h <= maxTextHAllowed) {
                break;
            }

            adjustedSize *= 0.9f;
            if (adjustedSize < 6f) {
                adjustedSize = 6f;
                textPaint.setTextSize(adjustedSize);
                break;
            }
        }

        // desenha cada box + texto
        for (int i = 0; i < count; i++) {
            int row = i / columns;
            int col = i % columns;

            int left = col * boxWidthPx;
            int top = row * boxHeightPx;
            int right = left + boxWidthPx;
            int bottom = top + boxHeightPx;

            RectF rect = new RectF(left + 1, top + 1, right - 1, bottom - 1);

            // borda arredondada
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, boxPaint);

            // texto
            String label = numbers.get(i);
            if (label == null) label = "";

            Rect tb = new Rect();
            textPaint.getTextBounds(label, 0, label.length(), tb);

            float cx = rect.centerX();
            float cy = rect.centerY();

            float textX = cx - (tb.width() / 2f) - tb.left;
            float textY = cy + (tb.height() / 2f) - tb.bottom;

            canvas.drawText(label, textX, textY, textPaint);
        }

        return bmp;
    }

    // ------------------------------------------------------------------------
    //  PARÁGRAFO EM CAIXA ARREDONDADA
    // ------------------------------------------------------------------------

    /**
     * Desenha um parágrafo inteiro dentro de um retângulo de cantos arredondados
     * (bordas + texto multiline) e envia isso como imagem raster segura.
     *
     * Exemplo:
     *   printer.printParagraphInRoundedBox(
     *       "Olho em redor do bar em que escrevo estas linhas...",
     *       24,  // fontSizePx
     *       16,  // paddingPx
     *       20   // radiusPx
     *   );
     *
     * @param bloco      Texto grande (pode conter quebras de linha ou ser longo)
     * @param fontPx     Tamanho da fonte em px (ex: 24)
     * @param paddingPx  Espaço interno entre borda e texto (ex: 16)
     * @param radiusPx   Raio dos cantos arredondados (ex: 20)
     */
    public void printParagraphInRoundedBox(String bloco,
                                           int fontPx,
                                           int paddingPx,
                                           int radiusPx) throws IOException {

        // largura do papel: 58mm ~384 dots. Se você tiver 80mm, mude pra ~576.
        final int paperDots = MAX_WIDTH_DOTS;

        // monta bitmap da caixa arredondada + texto
        Bitmap boxBmp = buildRoundedBoxBitmap(
                bloco,
                paperDots,
                fontPx,
                paddingPx,
                radiusPx
        );

        // imprime como imagem raster (fatiada)
        setAlign(1);
        printBitmapAsRasterStripes(boxBmp);
    }

    /**
     * Constrói um bitmap com:
     *  - fundo branco
     *  - uma borda arredondada envolvendo TODO o texto
     *  - texto autoquebrado em múltiplas linhas usando StaticLayout
     *
     * @param fullText    texto completo
     * @param maxWidthPx  largura máxima total (ex: 384)
     * @param fontSizePx  tamanho da fonte em px (ex: 24)
     * @param paddingPx   margem interna ao redor do texto (ex: 16)
     * @param radiusPx    raio dos cantos arredondados (ex: 20)
     */
    private Bitmap buildRoundedBoxBitmap(String fullText,
                                         int maxWidthPx,
                                         int fontSizePx,
                                         int paddingPx,
                                         int radiusPx) {

        // Configura pintura do texto
        TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFF000000);    // preto
        textPaint.setTextSize(fontSizePx); // tamanho da fonte em px

        // Largura interna disponível pro texto = maxWidthPx - padding*2
        int innerTextWidth = maxWidthPx - (paddingPx * 2);
        if (innerTextWidth < 50) innerTextWidth = 50; // fallback mínimo

        // Quebra o parágrafo automaticamente em várias linhas
        // StaticLayout antigo funciona em API baixa (sem Builder).
        StaticLayout staticLayout = new StaticLayout(
                fullText,
                textPaint,
                innerTextWidth,
                Layout.Alignment.ALIGN_NORMAL, // texto alinhado à esquerda dentro da caixa
                1.2f,   // multiplicador de espaçamento entre linhas
                0f,     // espaçamento adicional absoluto
                false
        );

        int textHeight = staticLayout.getHeight();
        int boxWidth   = innerTextWidth + paddingPx * 2;
        int boxHeight  = textHeight     + paddingPx * 2;

        // Cria bitmap branco com borda arredondada
        Bitmap bmp = Bitmap.createBitmap(boxWidth, boxHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // fundo branco
        canvas.drawColor(0xFFFFFFFF);

        // borda arredondada
        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3f);
        boxPaint.setColor(0xFF000000); // preto

        RectF rect = new RectF(
                1.5f,
                1.5f,
                boxWidth - 1.5f,
                boxHeight - 1.5f
        );

        canvas.drawRoundRect(rect, radiusPx, radiusPx, boxPaint);

        // desenha texto dentro (respeitando padding)
        canvas.save();
        canvas.translate(paddingPx, paddingPx);
        staticLayout.draw(canvas);
        canvas.restore();

        return bmp;
    }
}
