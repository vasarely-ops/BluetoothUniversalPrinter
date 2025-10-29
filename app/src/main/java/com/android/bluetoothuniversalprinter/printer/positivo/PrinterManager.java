package com.android.bluetoothuniversalprinter.printer.positivo;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;

import com.xcheng.printerservice.IPrinterCallback;
import com.xcheng.printerservice.IPrinterService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PrinterManager
 *
 * Camada de alto nível para impressão usando o serviço AIDL da XCheng.
 *
 * Objetivos:
 *  - Esconder bindService / Binder
 *  - Fornecer métodos parecidos com o seu driver ESC/POS antigo
 *    (txtPrint, printGrid, printRoundedGrid, printParagraphInRoundedBox,
 *     printQrCode, printCode128, feed etc.)
 *
 * IMPORTANTE:
 * 1) Este código assume que o equipamento já vem com o serviço
 *    com.xcheng.printerservice rodando no sistema (POS integrado).
 *
 * 2) A Action/Component do bind pode variar por fabricante/ROM.
 *    Abaixo está um chute razoável. Ajuste se necessário:
 *       - action: "com.xcheng.printer.PRINT_SERVICE"
 *       - package: "com.xcheng.printerservice"
 *
 * Se o vendor fornecer explicitamente um Intent diferente, altere em bind().
 */
public class PrinterManager {

    // Largura típica de cabeça térmica 58mm = ~384px. Ajuste para 80mm: ~576px.
    private static final int MAX_WIDTH_DOTS = 384;

    private static final String TAG = "PrinterManager";

    public interface StatusListener {
        // Use isso na Activity para atualizar UI ("Conectado", "Erro", etc.)
        void onStatusMessage(String msg);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final StatusListener uiListener;

    private IPrinterService service;      // Binder remoto
    private boolean bound = false;

    public PrinterManager(Context ctx, StatusListener listener) {
        this.context = ctx.getApplicationContext();
        this.uiListener = listener;
    }

    /* ----------------------------------------------------------------------------------------
     * 1) CONEXÃO COM O SERVIÇO AIDL
     * ---------------------------------------------------------------------------------------- */
    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IPrinterService.Stub.asInterface(binder);
            bound = true;
            logToUi("Impressora pronta (serviço ligado)");

            // podemos inicializar a impressora oficialmente:
            try {
                service.printerInit(new SimpleCallback("printerInit"));
            } catch (RemoteException e) {
                logError("printerInit falhou", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            logToUi("Serviço de impressora desconectado");
        }
    };

    /**
     * Faz o bind no serviço. Chame no onStart() da Activity, por exemplo.
     * Você só precisa chamar uma vez.
     */
    public void bind() {
        if (bound) return;

        // Ajuste isso se o fabricante/documentação informar outro action/class:
        Intent i = new Intent();
        i.setPackage("com.xcheng.printerservice");
        // algumas ROMs usam action explícita
        i.setAction("com.xcheng.printer.PRINT_SERVICE");
        // em alguns cenários você precisa setClassName direto:
        // i.setClassName("com.xcheng.printerservice", "com.xcheng.printerservice.PrintService");

        boolean ok = context.bindService(i, conn, Context.BIND_AUTO_CREATE);
        logToUi(ok ? "Conectando ao serviço de impressão..." :
                "Falha ao iniciar bindService()");
    }

    /**
     * Chame no onStop() / onDestroy() da Activity pra liberar.
     */
    public void unbind() {
        if (bound) {
            try {
                context.unbindService(conn);
            } catch (Exception ignored) {}
        }
        bound = false;
        service = null;
    }

    public boolean isReady() {
        return bound && service != null;
    }

    /* ----------------------------------------------------------------------------------------
     * 2) CALLBACK BÁSICO
     * ---------------------------------------------------------------------------------------- */
    /**
     * Callback simples pra logar resultado de cada chamada no serviço.
     * Você pode customizar pra dar Toast, etc.
     */
    private class SimpleCallback extends IPrinterCallback.Stub {
        private final String label;
        SimpleCallback(String label) {
            this.label = label;
        }

        @Override
        public void onException(int code, String msg) {
            logToUi("[" + label + "] ERRO(" + code + "): " + msg);
        }

        @Override
        public void onLength(long current, long total) {
            // progresso bruto, se o serviço usar spool base64 etc
            Log.d(TAG, "[" + label + "] length " + current + "/" + total);
        }

        @Override
        public void onRealLength(double realCurrent, double realTotal) {
            // idem acima, em mm impressos etc
            Log.d(TAG, "[" + label + "] realLen " + realCurrent + "/" + realTotal);
        }

        @Override
        public void onComplete() {
            logToUi("[" + label + "] completo");
        }
    }

    /* ----------------------------------------------------------------------------------------
     * 3) FUNÇÕES UTILITÁRIAS BÁSICAS (feed, texto, códigos, imagens)
     * ---------------------------------------------------------------------------------------- */

    /**
     * Alimenta papel (vários "line feeds").
     */
    public void feedLines(int n) {
        if (!isReady()) {
            logToUi("Impressora não conectada");
            return;
        }
        try {
            service.printWrapPaper(n, new SimpleCallback("feedLines"));
        } catch (Exception e) {
            logError("feedLines", e);
        }
    }

    /**
     * Pequeno helper pra montar atributos de texto.
     *
     * ATENÇÃO:
     * - As chaves do Map DEPENDEM DA IMPLEMENTAÇÃO REAL DO FABRICANTE!!
     *   Abaixo estão nomes genéricos comuns ("align", "bold", "scale").
     *   Ajuste conforme documentação oficial de com.xcheng.printerservice.
     */
    private Map makeTextAttr(int align, boolean bold, int scale) {
        Map<String, Object> map = new HashMap<>();
        // 0=left 1=center 2=right (mantemos sua convenção)
        map.put("align", align);
        // fontes grandes? no ESC/POS você usava scale 0,1,3 ...
        map.put("scale", scale);
        // negrito
        map.put("bold", bold ? 1 : 0);
        return map;
    }

    /**
     * Imprime uma linha de texto com alinhamento e escala (similar ao txtPrint antigo).
     * Ele já coloca "\n" no fim.
     *
     * @param text  texto
     * @param align 0=esquerda,1=centro,2=direita
     * @param scale 0=normal,1=2x,3=3x (vai pro Map de atributos)
     */
    public void printTextLine(String text, int align, int scale) {
        if (!isReady()) {
            logToUi("Impressora não conectada");
            return;
        }
        try {
            Map attrs = makeTextAttr(align, (scale > 0), scale);
            service.printTextWithAttributes(
                    text + "\n",
                    attrs,
                    new SimpleCallback("printTextLine")
            );
        } catch (Exception e) {
            logError("printTextLine", e);
        }
    }

    /**
     * Imprime um QRCode nativo do serviço.
     * @param data conteúdo
     * @param align 0=left 1=center 2=right
     * @param size "tamanho do módulo" (típico range 4..10). Ajustar se necessário.
     */
    public void printQrCode(String data, int align, int size) {
        if (!isReady()) {
            logToUi("Impressora não conectada");
            return;
        }
        try {
            service.printQRCode(
                    data,
                    align,
                    size,
                    new SimpleCallback("printQRCode")
            );
        } catch (Exception e) {
            logError("printQrCode", e);
        }
    }

    /**
     * Imprime código de barras CODE128.
     *
     * @param data conteúdo numérico/string
     * @param align 0=left,1=center,2=right
     * @param width largura total em px/barras (depende do serviço)
     * @param height altura em px
     * @param showText se true imprime os dígitos embaixo
     */
    public void printCode128(String data, int align, int width, int height, boolean showText) {
        if (!isReady()) {
            logToUi("Impressora não conectada");
            return;
        }
        try {
            service.printBarCode(
                    data,
                    align,
                    width,
                    height,
                    showText,
                    new SimpleCallback("printBarCode")
            );
        } catch (Exception e) {
            logError("printCode128", e);
        }
    }

    /**
     * Imprime um bitmap arbitrário, já centralizando (align=1).
     * O serviço geralmente faz dithering e corte interno.
     */
    public void printBitmap(Bitmap bmp, int align) {
        if (!isReady()) {
            logToUi("Impressora não conectada");
            return;
        }
        if (bmp == null) {
            logToUi("Bitmap nulo");
            return;
        }

        // Ajusta largura pra não estourar a boca (384px 58mm).
        Bitmap scaled = ensureMaxWidth(bmp, MAX_WIDTH_DOTS);

        try {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("align", align); // 0/1/2 (ajuste se a lib usar outra chave)
            service.printBitmapWithAttributes(
                    scaled,
                    attrs,
                    new SimpleCallback("printBitmap")
            );
        } catch (Exception e) {
            logError("printBitmap", e);
        }
    }

    /* ----------------------------------------------------------------------------------------
     * 4) FUNÇÕES AVANÇADAS (equivalentes aos seus recursos gráficos personalizados)
     * ---------------------------------------------------------------------------------------- */

    // === 4.1 GRID DE NÚMEROS EM CÍRCULOS =========================

    /**
     * Interface pública parecida com a sua antiga printGrid(...)
     * Ela gera um bitmap com bolinhas numeradas e manda pro serviço.
     *
     * @param numbers ex: ["01","02","03",...]
     * @param columns quantas colunas
     * @param radiusPx raio do círculo, em px
     * @param textSizePx tamanho de texto desejado, em px (ajustamos se não couber)
     */
    public void printGridCircles(
            List<String> numbers,
            int columns,
            int radiusPx,
            float textSizePx
    ) {
        if (!isReady()) {
            logToUi("Impressora não conectada");
            return;
        }

        Bitmap gridBmp = buildCircleGridBitmap(numbers, columns, radiusPx, textSizePx);
        gridBmp = ensureMaxWidth(gridBmp, MAX_WIDTH_DOTS);

        printBitmap(gridBmp, /*align=*/1);
    }

    public void printGridCircles(
            String[] numbers,
            int columns,
            int radiusPx,
            float textSizePx
    ) {
        printGridCircles(Arrays.asList(numbers), columns, radiusPx, textSizePx);
    }

    public void printGridCircles(
            int[] numbers,
            int columns,
            int radiusPx,
            float textSizePx
    ) {
        List<String> tmp = new ArrayList<>();
        for (int n : numbers) {
            tmp.add(String.format("%02d", n));
        }
        printGridCircles(tmp, columns, radiusPx, textSizePx);
    }

    /**
     * Monta bitmap com círculos e números centralizados.
     * (Baseado no seu buildGridBitmap anterior)
     */
    private Bitmap buildCircleGridBitmap(
            List<String> numbers,
            int columns,
            int radiusPx,
            float wantedTextSizePx
    ) {
        if (columns < 1) columns = 1;
        if (radiusPx < 6) radiusPx = 6;
        if (wantedTextSizePx < 6f) wantedTextSizePx = 6f;

        final int n = numbers.size();
        final int rows = (n + columns - 1) / columns;

        // padding interno dentro da célula
        final int pad = Math.max(4, radiusPx / 2);
        final int cellSize = radiusPx * 2 + pad * 2;

        final int bmpWidth = columns * cellSize;
        final int bmpHeight = rows * cellSize;

        Bitmap bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        canvas.drawColor(Color.WHITE);

        Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        circle.setStyle(Paint.Style.STROKE);
        circle.setColor(Color.BLACK);
        circle.setStrokeWidth(Math.max(2f, radiusPx / 8f));

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        // ajusta fonte pra caber no círculo
        float adjustedTextSize = fitTextSizeToCircle(wantedTextSizePx, radiusPx, textPaint);

        for (int i = 0; i < n; i++) {
            int row = i / columns;
            int col = i % columns;

            int left = col * cellSize;
            int top = row * cellSize;

            float cx = left + cellSize / 2f;
            float cy = top + cellSize / 2f;

            canvas.drawCircle(cx, cy, radiusPx, circle);

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

    /**
     * Calcula o tamanho máximo da fonte que cabe dentro do círculo.
     * Usa "88" como pior caso.
     */
    private float fitTextSizeToCircle(float wantedTextSizePx,
                                      int radiusPx,
                                      Paint textPaint) {

        float adjusted = wantedTextSizePx;
        Rect bounds = new Rect();
        final float maxSpan = radiusPx * 1.6f; // ~80% do diâmetro como você já fazia

        if (adjusted < 6f) adjusted = 6f;

        while (true) {
            textPaint.setTextSize(adjusted);
            textPaint.getTextBounds("88", 0, 2, bounds);

            float w = bounds.width();
            float h = bounds.height();

            if (w <= maxSpan && h <= maxSpan) {
                return adjusted;
            }

            adjusted *= 0.9f;
            if (adjusted < 6f) {
                return 6f;
            }
        }
    }

    // === 4.2 GRID DE NÚMEROS EM RETÂNGULOS ARREDONDADOS =========

    public void printRoundedGrid(
            List<String> numbers,
            int columns,
            int boxWidthPx,
            int boxHeightPx,
            int cornerRadiusPx,
            float textSizePxWanted
    ) {
        if (!isReady()) {
            logToUi("Impressora não conectada");
            return;
        }

        Bitmap gridBmp = buildRoundedGridBitmap(
                numbers,
                columns,
                boxWidthPx,
                boxHeightPx,
                cornerRadiusPx,
                textSizePxWanted
        );

        gridBmp = ensureMaxWidth(gridBmp, MAX_WIDTH_DOTS);

        printBitmap(gridBmp, /*align=*/1);
    }

    public void printRoundedGrid(
            String[] numbers,
            int columns,
            int boxWidthPx,
            int boxHeightPx,
            int cornerRadiusPx,
            float textSizePxWanted
    ) {
        List<String> list = Arrays.asList(numbers);
        printRoundedGrid(list, columns, boxWidthPx, boxHeightPx, cornerRadiusPx, textSizePxWanted);
    }

    public void printRoundedGrid(
            int[] numbers,
            int columns,
            int boxWidthPx,
            int boxHeightPx,
            int cornerRadiusPx,
            float textSizePxWanted
    ) {
        List<String> list = new ArrayList<>();
        for (int n : numbers) list.add(String.format("%02d", n));
        printRoundedGrid(list, columns, boxWidthPx, boxHeightPx, cornerRadiusPx, textSizePxWanted);
    }

    /**
     * Constrói o bitmap da grade retangular (baseado no seu buildRoundedGridBitmap antigo).
     */
    private Bitmap buildRoundedGridBitmap(
            List<String> numbers,
            int columns,
            int boxWidthPx,
            int boxHeightPx,
            int cornerRadiusPx,
            float wantedTextSizePx
    ) {
        if (columns < 1) columns = 1;
        if (boxWidthPx < 20) boxWidthPx = 20;
        if (boxHeightPx < 20) boxHeightPx = 20;
        if (cornerRadiusPx < 2) cornerRadiusPx = 2;
        if (wantedTextSizePx < 6f) wantedTextSizePx = 6f;

        final int count = numbers.size();
        final int rows = (count + columns - 1) / columns;

        final int bmpW = columns * boxWidthPx;
        final int bmpH = rows * boxHeightPx;

        Bitmap bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);

        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setColor(Color.BLACK);
        boxPaint.setStrokeWidth(2f);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        // cabe dentro da caixa?
        float adjustedSize = fitTextSizeToBox(wantedTextSizePx, boxWidthPx, boxHeightPx, textPaint);

        for (int i = 0; i < count; i++) {
            int row = i / columns;
            int col = i % columns;

            int left = col * boxWidthPx;
            int top = row * boxHeightPx;
            int right = left + boxWidthPx;
            int bottom = top + boxHeightPx;

            RectF rect = new RectF(left + 1, top + 1, right - 1, bottom - 1);
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, boxPaint);

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

    private float fitTextSizeToBox(
            float wantedTextSizePx,
            int boxWidthPx,
            int boxHeightPx,
            Paint textPaint
    ) {
        if (wantedTextSizePx < 6f) wantedTextSizePx = 6f;

        final float textPadding = 4f;
        final float maxW = boxWidthPx - 2f * textPadding;
        final float maxH = boxHeightPx - 2f * textPadding;

        float size = wantedTextSizePx;
        Rect bounds = new Rect();

        while (true) {
            textPaint.setTextSize(size);
            textPaint.getTextBounds("88", 0, 2, bounds);

            float w = bounds.width();
            float h = bounds.height();

            if (w <= maxW && h <= maxH) {
                return size;
            }

            size *= 0.9f;
            if (size < 6f) {
                return 6f;
            }
        }
    }

    // === 4.3 PARÁGRAFO DENTRO DE CAIXA ARREDONDADA ===============

    /**
     * Equivalente ao seu printParagraphInRoundedBox.
     *
     * Gera um bitmap com:
     * - fundo branco
     * - retângulo c/ cantos arredondados
     * - texto quebrado automaticamente em múltiplas linhas
     *
     * Depois manda esse bitmap pro serviço de impressão.
     *
     * @param text   bloco (pode ter \n)
     * @param fontPx tamanho da fonte
     * @param paddingPx padding interno
     * @param radiusPx raio da borda arredondada
     */
    public void printParagraphInRoundedBox(String text, int fontPx, int paddingPx, int radiusPx) {
        if (!isReady()) {
            logToUi("Impressora não conectada");
            return;
        }

        Bitmap boxBmp = buildRoundedBoxBitmap(
                text,
                MAX_WIDTH_DOTS, // tentar usar largura máxima já
                fontPx,
                paddingPx,
                radiusPx
        );

        // (já está <= MAX_WIDTH_DOTS, mas vamos garantir)
        boxBmp = ensureMaxWidth(boxBmp, MAX_WIDTH_DOTS);

        printBitmap(boxBmp, /*align=*/0); // alinhado à esquerda normalmente
    }

    /**
     * Gera o bitmap da caixa arredondada com o texto multiline.
     * Equivalente às suas versões buildRoundedBoxBitmap.
     */
    private Bitmap buildRoundedBoxBitmap(
            String fullText,
            int maxWidthPx,
            int fontSizePx,
            int paddingPx,
            int radiusPx
    ) {
        if (paddingPx < 4) paddingPx = 4;
        if (radiusPx < 4) radiusPx = 4;

        // 1) Configura texto
        TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(fontSizePx);

        int innerTextWidth = maxWidthPx - (paddingPx * 2);
        if (innerTextWidth < 50) innerTextWidth = 50;

        // StaticLayout quebra o texto automaticamente em várias linhas
        StaticLayout staticLayout = new StaticLayout(
                fullText,
                textPaint,
                innerTextWidth,
                Layout.Alignment.ALIGN_NORMAL,
                1.2f, // multiplicador de espaçamento de linha
                0f,   // espaçamento extra
                false
        );

        int textHeight = staticLayout.getHeight();
        int boxWidth   = innerTextWidth + paddingPx * 2;
        int boxHeight  = textHeight     + paddingPx * 2;

        // 2) Cria bitmap final
        Bitmap bmp = Bitmap.createBitmap(boxWidth, boxHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // fundo branco
        canvas.drawColor(Color.WHITE);

        // 3) Desenha borda arredondada
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

        // 4) Desenha o texto dentro (offset = padding)
        canvas.save();
        canvas.translate(paddingPx, paddingPx);
        staticLayout.draw(canvas);
        canvas.restore();

        return bmp;
    }

    /* ----------------------------------------------------------------------------------------
     * 5) HELPERS GERAIS
     * ---------------------------------------------------------------------------------------- */

    /**
     * Garante que a largura do bitmap não ultrapasse MAX_WIDTH_DOTS.
     * Mantém a proporção.
     */
    private Bitmap ensureMaxWidth(Bitmap src, int maxWidthPx) {
        if (src.getWidth() <= maxWidthPx) {
            return src;
        }
        float ratio = (float) maxWidthPx / (float) src.getWidth();
        int newH = Math.round(src.getHeight() * ratio);
        return Bitmap.createScaledBitmap(src, maxWidthPx, newH, true);
    }

    private void logToUi(String msg) {
        Log.d(TAG, msg);
        if (uiListener != null) {
            mainHandler.post(() -> uiListener.onStatusMessage(msg));
        }
    }

    private void logError(String where, Exception e) {
        Log.e(TAG, where + " ERROR", e);
        logToUi(where + " erro: " + e.getMessage());
    }
}
