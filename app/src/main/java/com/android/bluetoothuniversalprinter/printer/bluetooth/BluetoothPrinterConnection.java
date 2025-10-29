package com.android.bluetoothuniversalprinter.printer.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Mantém a conexão Bluetooth SPP viva.
 *
 * Uso:
 *   BluetoothPrinterConnection conn = new BluetoothPrinterConnection();
 *   conn.connect(btAdapter, mac, SPP_UUID); // fazer em thread fora da UI
 *   OutputStream out = conn.getOutputStream();
 *   // passar esse out pro BluetoothEscPosPrinter
 *   ...
 *   conn.close();
 */
public class BluetoothPrinterConnection {

    private BluetoothSocket socket;

    /** Conecta via MAC + UUID SPP. */
    public void connect(BluetoothAdapter adapter, String macAddress, UUID sppUuid) throws IOException {
        close(); // fecha se já tinha algo aberto
        BluetoothDevice device = adapter.getRemoteDevice(macAddress);
        socket = device.createRfcommSocketToServiceRecord(sppUuid);
        socket.connect();
    }

    /** Está conectado? */
    public boolean isConnected() {
        return socket != null && socket.isConnected();
    }

    /** OutputStream bruto para escrever ESC/POS. */
    public OutputStream getOutputStream() throws IOException {
        if (!isConnected()) throw new IOException("Bluetooth não conectado");
        return socket.getOutputStream();
    }

    /** Fecha conexão com segurança. */
    public void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {}
            socket = null;
        }
    }
}
