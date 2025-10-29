package com.android.bluetoothuniversalprinter.printer.positivo;


import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.RemoteException;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;

import com.xcheng.printerservice.IPrinterCallback;
import com.xcheng.printerservice.IPrinterService;

import java.util.Arrays;
import java.util.List;

/**
 * Renderizador gráfico pro backend interno (POSITIVO / L500 via AIDL).
 *
 * Ideia:
 * - Gera bitmaps com círculos numerados, caixas arredondadas numeradas,
 *   e parágrafo dentro de caixa arredondada.
 * - Manda esses bitmaps pra POS via IPrinterService.printBitmap().
 *
 * Observação importante:
 *  Papel 58mm típico = ~384 dots úteis.
 *  Se o bitmap ficar mais largo, reduzimos proporcionalmente.
 */
public class AidlGraphicsPrinter {

    private static final String TAG = "AidlGraphicsPrinter";

    /** Largura útil típica da cabeça térmica 58mm (POSITIVO/L500): ~384 dots. */
    private static final int PAPER_MAX_WIDTH_DOTS = 384;
    private static final int MAX_WIDTH_DOTS = 300;

    private final IPrinterService svc;
    private final IPrinterCallback cb;

    public AidlGraphicsPrinter(IPrinterService svc, IPrinterCallback cb) {
        this.svc = svc;
        this.cb = cb;
    }

    /* ---------------------------------------------------------
     * Função util: garante largura máxima
     * --------------------------------------------------------- */
    private Bitmap ensureMaxWidth(Bitmap bmp) {
        if (bmp == null) return null;
        if (bmp.getWidth() <= PAPER_MAX_WIDTH_DOTS) return bmp;

        float scale = (float) PAPER_MAX_WIDTH_DOTS / (float) bmp.getWidth();
        int newW = PAPER_MAX_WIDTH_DOTS;
        int newH = Math.max(1, Math.round(bmp.getHeight() * scale));
        return Bitmap.createScaledBitmap(bmp, newW, newH, true);
    }

    /* ---------------------------------------------------------
     * 1) Grade de bolinhas numeradas (equivalente ao printGrid do ESC/POS)
     * --------------------------------------------------------- */

    public void printCircleGrid(String[] numbers,
                                int columns,
                                int radiusPx,
                                float textSizePxWanted) throws RemoteException {

        printCircleGrid(Arrays.asList(numbers), columns, radiusPx, textSizePxWanted);
    }

    public void printCircleGrid(List<String> numbers,
                                int columns,
                                int radiusPx,
                                float textSizePxWanted) throws RemoteException {

        if (numbers == null || numbers.isEmpty()) return;
        if (columns < 1) columns = 1;
        if (radiusPx < 6) radiusPx = 6;
        if (textSizePxWanted < 6f) textSizePxWanted = 6f;

        Bitmap gridBmp = buildCircleGridBitmap(numbers, columns, radiusPx, textSizePxWanted);
        gridBmp = ensureMaxWidth(gridBmp);

        if (gridBmp != null) {
            svc.printBitmap(gridBmp, cb);
            svc.printWrapPaper(2, cb); // alimenta papel depois
        } else {
            Log.e(TAG, "printCircleGrid: gridBmp nulo");
        }
    }

    private Bitmap buildCircleGridBitmap(List<String> numbers,
                                         int columns,
                                         int radiusPx,
                                         float wantedTextSizePx) {

        final int n = numbers.size();
        final int rows = (n + columns - 1) / columns;

        // padding interno entre borda da célula e o círculo
        final int pad = Math.max(4, radiusPx / 2);

        // cada célula é quadrada: círculo centralizado
        final int cellSize = radiusPx * 2 + pad * 2;

        final int bmpWidth = columns * cellSize;
        final int bmpHeight = rows * cellSize;

        Bitmap bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // fundo branco
        canvas.drawColor(Color.WHITE);

        // caneta do círculo
        Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        circle.setStyle(Paint.Style.STROKE);
        circle.setColor(Color.BLACK);
        circle.setStrokeWidth(Math.max(2f, radiusPx / 8f));

        // caneta do texto
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        // ajusta fonte pra caber dentro da bolinha
        float adjustedTextSize = wantedTextSizePx;
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

        // desenha células
        for (int i = 0; i < n; i++) {
            int row = i / columns;
            int col = i % columns;

            int left = col * cellSize;
            int top = row * cellSize;

            float cx = left + cellSize / 2f;
            float cy = top + cellSize / 2f;

            // bolinha
            canvas.drawCircle(cx, cy, radiusPx, circle);

            // texto centralizado na bolinha
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

    /* ---------------------------------------------------------
     * 2) Grade de retângulos arredondados (equivalente ao printRoundedGrid do ESC/POS)
     * --------------------------------------------------------- */

    public void printRoundedGrid(String[] numbers,
                                 int columns,
                                 int boxWidthPx,
                                 int boxHeightPx,
                                 int cornerRadiusPx,
                                 float textSizePxWanted) throws RemoteException {

        printRoundedGrid(
                Arrays.asList(numbers),
                columns,
                boxWidthPx,
                boxHeightPx,
                cornerRadiusPx,
                textSizePxWanted
        );
    }

    public void printRoundedGrid(List<String> numbers,
                                 int columns,
                                 int boxWidthPx,
                                 int boxHeightPx,
                                 int cornerRadiusPx,
                                 float textSizePxWanted) throws RemoteException {

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

        gridBmp = ensureMaxWidth(gridBmp);

        if (gridBmp != null) {
            svc.printBitmap(gridBmp, cb);
            svc.printWrapPaper(2, cb);
        } else {
            Log.e(TAG, "printRoundedGrid: gridBmp nulo");
        }
    }

    private Bitmap buildRoundedGridBitmap(List<String> numbers,
                                          int columns,
                                          int boxWidthPx,
                                          int boxHeightPx,
                                          int cornerRadiusPx,
                                          float wantedTextSizePx) {

        final int count = numbers.size();
        final int rows = (count + columns - 1) / columns;

        final int bmpW = columns * boxWidthPx;
        final int bmpH = rows * boxHeightPx;

        Bitmap bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // fundo branco
        canvas.drawColor(Color.WHITE);

        // caneta da borda arredondada
        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setColor(Color.BLACK);
        boxPaint.setStrokeWidth(2f);

        // texto
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        // Ajusta a fonte pra caber dentro da caixa
        float adjustedSize = wantedTextSizePx;
        Rect bounds = new Rect();

        final float padding = 4f;
        final float maxTextWAllowed = boxWidthPx  - 2f * padding;
        final float maxTextHAllowed = boxHeightPx - 2f * padding;

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

        // desenha cada caixinha + número
        for (int i = 0; i < count; i++) {
            int row = i / columns;
            int col = i % columns;

            int left   = col * boxWidthPx;
            int top    = row * boxHeightPx;
            int right  = left + boxWidthPx;
            int bottom = top + boxHeightPx;

            RectF rect = new RectF(left + 1, top + 1, right - 1, bottom - 1);

            // borda arredondada
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, boxPaint);

            // texto centralizado
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

    /* ---------------------------------------------------------
     * 3) Parágrafo dentro de caixa arredondada
     *    (equivalente a printParagraphInRoundedBox do ESC/POS)
     * --------------------------------------------------------- */

    public void printParagraphInRoundedBox(String fullText,
                                           int fontPx,
                                           int paddingPx,
                                           int radiusPx) throws RemoteException {

        // Largura alvo = boca da impressora
        Bitmap boxBmp = buildRoundedBoxBitmap(
                fullText,
                PAPER_MAX_WIDTH_DOTS,
                fontPx,
                paddingPx,
                radiusPx
        );

        // boxBmp já nasce com PAPER_MAX_WIDTH_DOTS de largura, mas vamos garantir:
        boxBmp = ensureMaxWidth(boxBmp);

        if (boxBmp != null) {
            svc.printBitmap(boxBmp, cb);
            svc.printWrapPaper(2, cb);
        } else {
            Log.e(TAG, "printParagraphInRoundedBox: boxBmp nulo");
        }
    }

    /**
     * Renderiza um bloco de texto dentro de uma caixa de cantos arredondados.
     * Usa StaticLayout para autoquebrar as linhas.
     */
    @SuppressWarnings("deprecation") // StaticLayout antigo pra compatibilidade
    private Bitmap buildRoundedBoxBitmap(String fullText,
                                         int maxWidthPx,
                                         int fontSizePx,
                                         int paddingPx,
                                         int radiusPx) {

        // pinta texto
        TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(fontSizePx);

        // largura interna disponível pro texto
        int innerTextWidth = maxWidthPx - (paddingPx * 2);
        if (innerTextWidth < 50) innerTextWidth = 50; // fallback

        StaticLayout staticLayout = new StaticLayout(
                fullText,
                textPaint,
                innerTextWidth,
                Layout.Alignment.ALIGN_NORMAL, // texto alinhado à esquerda
                1.2f,  // espaçamento entrelinhas
                0f,
                false
        );

        int textHeight = staticLayout.getHeight();
        int boxWidth   = innerTextWidth + paddingPx * 2;
        int boxHeight  = textHeight     + paddingPx * 2;

        Bitmap bmp = Bitmap.createBitmap(boxWidth, boxHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // fundo branco
        canvas.drawColor(Color.WHITE);

        // borda arredondada
        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3f);
        boxPaint.setColor(Color.BLACK);

        RectF rect = new RectF(
                1.5f,
                1.5f,
                boxWidth - 1.5f,
                boxHeight - 1.5f
        );
        canvas.drawRoundRect(rect, radiusPx, radiusPx, boxPaint);

        // desenha o parágrafo dentro (respeitando padding)
        canvas.save();
        canvas.translate(paddingPx, paddingPx);
        staticLayout.draw(canvas);
        canvas.restore();

        return bmp;
    }
    private Bitmap buildCustomFontTextBitmap(
            Context ctx,
            String text,
            String fontAssetName,   // "Quivert.otf"
            float textSizePx,
            int align,              // 0=esq,1=centro,2=dir
            int paddingPx
    ) {
        try {
            Typeface tf = Typeface.createFromAsset(ctx.getAssets(), fontAssetName);

            TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setTypeface(tf);
            paint.setTextSize(textSizePx);

            int maxContentWidthPx = MAX_WIDTH_DOTS - (paddingPx * 2);
            if (maxContentWidthPx <= 0) return null;

            Layout.Alignment layoutAlign;
            switch (align) {
                case 1:  layoutAlign = Layout.Alignment.ALIGN_CENTER;   break;
                case 2:  layoutAlign = Layout.Alignment.ALIGN_OPPOSITE; break;
                default: layoutAlign = Layout.Alignment.ALIGN_NORMAL;   break;
            }

            StaticLayout staticLayout = StaticLayout.Builder
                    .obtain(text, 0, text.length(), paint, maxContentWidthPx)
                    .setAlignment(layoutAlign)
                    .setIncludePad(false)
                    .build();

            int textW = staticLayout.getWidth();
            int textH = staticLayout.getHeight();

            int bmpW = textW + paddingPx * 2;
            int bmpH = textH + paddingPx * 2;
            if (bmpW <= 0 || bmpH <= 0) return null;

            Bitmap bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(Color.WHITE);

            canvas.save();
            canvas.translate(paddingPx, paddingPx);
            staticLayout.draw(canvas);
            canvas.restore();

            return bmp;
        } catch (Exception e) {
            Log.e("AidlGraphicsPrinter", "buildCustomFontTextBitmap failed", e);
            return null;
        }
    }

    /**
     * Imprime texto com fonte custom via impressora interna (svc).
     * Ele gera um bitmap e manda usar printBitmap do serviço AIDL.
     */
    public void printCustomFontText(
            Context ctx,
            String text,
            String fontAssetName,
            float textSizePx,
            int align,
            int paddingPx
    ) throws android.os.RemoteException {

        Bitmap bmp = buildCustomFontTextBitmap(
                ctx,
                text,
                fontAssetName,
                textSizePx,
                align,
                paddingPx
        );

        if (bmp == null) {
            // fallback simples se der ruim no bitmap:
            svc.printText("<<Falha gerar fonte personalizada>>\n", cb);
            svc.printWrapPaper(2, cb);
            return;
        }

        // manda o bitmap pro firmware interno da impressora POSITIVO/L500
        svc.printBitmap(bmp, cb);

        // alimenta papel
        svc.printWrapPaper(2, cb);
    }
}
