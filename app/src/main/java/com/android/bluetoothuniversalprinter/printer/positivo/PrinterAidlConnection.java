package com.android.bluetoothuniversalprinter.printer.positivo;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import com.xcheng.printerservice.IPrinterService;

/**
 * Faz bind no serviço AIDL da impressora XCheng.
 *
 * Fluxo típico:
 *   PrinterAidlConnection conn = new PrinterAidlConnection();
 *   conn.connect(context);
 *   ...
 *   IPrinterService svc = conn.getService();
 *   if (svc != null) { ... }
 *
 *   conn.disconnect();
 */
public class PrinterAidlConnection {

    private static final String TAG = "PrinterAidlConnection";

    private IPrinterService service;
    private boolean isBound = false;

    private final ServiceConnection svcConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IPrinterService.Stub.asInterface(binder);
            isBound = true;
            Log.d(TAG, "onServiceConnected: printer service ligado.");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "onServiceDisconnected: printer service caiu.");
            service = null;
            isBound = false;
        }
    };

    /**
     * Faz bind no serviço. Deve ser chamado cedo (ex: onCreate da Activity).
     * IMPORTANTE:
     *   - Ajuste o Intent setPackage / setClassName se o vendor usar outro package/Service.
     */
    public void connect(Context ctx) {
        if (isBound) return;

        try {
            Intent intent = new Intent();
            // Geralmente vendors expõem algo assim:
            // "com.xcheng.printerservice" como package
            // e um Service tipo "com.xcheng.printerservice.PrinterService"
            // Se o vendor documentar outro nome, ajuste aqui.
            intent.setPackage("com.xcheng.printerservice");
            intent.setAction("com.xcheng.printerservice.IPrinterService");

            boolean ok = ctx.bindService(intent, svcConn, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "bindService retornou: " + ok);
        } catch (Exception e) {
            Log.e(TAG, "Falha ao bindar serviço de impressão", e);
        }
    }

    /**
     * Desfaz o bind. Chamar em onDestroy().
     */
    public void disconnect(Context ctx) {
        if (isBound) {
            try {
                ctx.unbindService(svcConn);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao unbindService", e);
            }
        }
        service = null;
        isBound = false;
    }

    public IPrinterService getService() {
        return service;
    }

    public boolean isConnected() {
        return (service != null && isBound);
    }
}
