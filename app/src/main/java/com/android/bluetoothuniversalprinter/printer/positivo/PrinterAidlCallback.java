package com.android.bluetoothuniversalprinter.printer.positivo;

import android.util.Log;

import com.xcheng.printerservice.IPrinterCallback;

/**
 * Callback padrão pra operações AIDL.
 * A impressora chama isso async pra avisar status.
 *
 * Você pode passar uma UIListener pra atualizar tela quando terminar/imprimir.
 */
public class PrinterAidlCallback extends IPrinterCallback.Stub {

    public interface UIListener {
        void onDone();
        void onError(int code, String msg);
        void onProgress(long current, long total);
    }

    private static final String TAG = "PrinterAidlCallback";

    private final UIListener listener;

    public PrinterAidlCallback(UIListener listener) {
        this.listener = listener;
    }

    @Override
    public void onException(int code, String msg) {
        Log.e(TAG, "onException code=" + code + " msg=" + msg);
        if (listener != null) {
            listener.onError(code, msg);
        }
    }

    @Override
    public void onLength(long current, long total) {
        Log.d(TAG, "onLength current=" + current + " total=" + total);
        if (listener != null) {
            listener.onProgress(current, total);
        }
    }

    @Override
    public void onRealLength(double realCurrent, double realTotal) {
        Log.d(TAG, "onRealLength realCurrent=" + realCurrent + " realTotal=" + realTotal);
        // pode ignorar ou também chamar onProgress
    }

    @Override
    public void onComplete() {
        Log.d(TAG, "onComplete");
        if (listener != null) {
            listener.onDone();
        }
    }
}
