package com.android.bluetoothuniversalprinter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.android.bluetoothuniversalprinter.printer.bluetooth.BluetoothEscPosPrinter;
import com.android.bluetoothuniversalprinter.printer.bluetooth.BluetoothPrinterConnection;
import com.android.bluetoothuniversalprinter.printer.bluetooth.PrinterDevice;
import com.xcheng.printerservice.IPrinterCallback;
import com.xcheng.printerservice.IPrinterService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Comportamento:
 *
 * - Detecta fabricante do terminal (ex.: PAX, SUNMI, GERTEC, POSITIVO, L500).
 * - Define backend de impressão:
 *      PAX / SUNMI / GERTEC => BLUETOOTH ESC/POS
 *      POSITIVO / L500      => AIDL interno
 *      DESCONHECIDO         => BLUETOOTH ESC/POS (fallback)
 *
 * - Se backend = BLUETOOTH:
 *      * Mostra botão de scan.
 *      * Faz scan, lista via AlertDialog, conecta e salva MAC em SharedPreferences.
 *      * Ao abrir o app, tenta reconectar na última impressora usada.
 *
 * - Se backend = AIDL:
 *      * Esconde botão de scan.
 *      * Faz bind no serviço IPrinterService.
 *      * As rotinas de impressão chamam direto o serviço, sem Bluetooth.
 *
 * - Os botões de impressão usam a mesma assinatura, mas internamente
 *   chamam Bluetooth ou AIDL conforme o backend selecionado.
 */
public class MainActivity extends AppCompatActivity {

    /* =====================================================
     * Tipos auxiliares
     * ===================================================== */
    private enum Manufacturer {
        PAX, SUNMI, GERTEC, POSITIVO, L500, DESCONHECIDO
    }

    private enum PrintBackend {
        BLUETOOTH, AIDL
    }

    /* =====================================================
     * Constantes / chaves
     * ===================================================== */
    private static final String TAG = "MainActivityPrinter";
    private static final String PREFS_NAME = "printer_prefs";
    private static final String PREF_KEY_MAC = "printer_mac";
    private static final String PREF_KEY_NAME = "printer_name";

    // UUID SPP padrão para impressoras térmicas ESC/POS clássicas
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    /* =====================================================
     * UI
     * ===================================================== */
    private TextView txtDeviceModel;
    private TextView txtPrintMode;
    private TextView txtStatus;
    private TextView txtHint;
    private Button btnScan;

    private Button btnPrintAlign;
    private Button btnPrintImage;
    private Button btnPrintQr;
    private Button btnPrintCode128;
    private Button btnCut;
    private Button btnPrintSizes;
    private Button btnPrintRoundedGrid;
    private Button btnPrintTextRounded;

    /* =====================================================
     * Estado de plataforma / backend
     * ===================================================== */
    private Manufacturer deviceManufacturer = Manufacturer.DESCONHECIDO;
    private PrintBackend backend = PrintBackend.BLUETOOTH;

    /* =====================================================
     * Bluetooth / Impressora ESC/POS
     * ===================================================== */
    private BluetoothAdapter btAdapter;
    private final List<PrinterDevice> foundDevices = new ArrayList<>();

    private BluetoothPrinterConnection btConn = null;
    private BluetoothEscPosPrinter escPosPrinter = null;

    private boolean receiverRegistered = false;

    /* =====================================================
     * AIDL / Impressora interna POS
     * ===================================================== */
    private IPrinterService aidlPrinterService = null;
    private boolean aidlBound = false;

    private final ServiceConnection aidlConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            aidlPrinterService = IPrinterService.Stub.asInterface(service);
            aidlBound = true;
            runOnUiThread(() -> {
                txtStatus.setText("Status: Serviço interno conectado");
                Toast.makeText(MainActivity.this,
                        "Impressora interna pronta",
                        Toast.LENGTH_SHORT
                ).show();
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            aidlBound = false;
            aidlPrinterService = null;
            runOnUiThread(() -> txtStatus.setText("Status: Serviço interno desconectado"));
        }
    };

    // Callback "genérico" para operações de impressão no AIDL
    private final IPrinterCallback aidlCallback = new IPrinterCallback.Stub() {
        @Override
        public void onException(int code, String msg) {
            Log.e(TAG, "AIDL onException: " + code + " / " + msg);
        }

        @Override
        public void onLength(long current, long total) {
            Log.d(TAG, "AIDL onLength: " + current + "/" + total);
        }

        @Override
        public void onRealLength(double realCurrent, double realTotal) {
            Log.d(TAG, "AIDL onRealLength: " + realCurrent + "/" + realTotal);
        }

        @Override
        public void onComplete() {
            Log.d(TAG, "AIDL onComplete()");
            runOnUiThread(() -> Toast.makeText(
                    MainActivity.this,
                    "Impresso (AIDL)",
                    Toast.LENGTH_SHORT
            ).show());
        }
    };

    /* =====================================================
     * Trabalho I/O fora da UI
     * ===================================================== */
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /* =====================================================
     * Permissões runtime (apenas p/ Bluetooth backend)
     * ===================================================== */
    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> Log.d(TAG, "permissões: " + result)
            );

    /* =====================================================
     * BroadcastReceiver para descoberta Bluetooth
     * (só usado se backend == BLUETOOTH)
     * ===================================================== */
    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) return;

                String name = (device.getName() == null) ? "Sem Nome" : device.getName();
                String addr = device.getAddress();

                // evita duplicados
                for (PrinterDevice d : foundDevices) {
                    if (d.address.equals(addr)) {
                        return;
                    }
                }
                foundDevices.add(new PrinterDevice(name, addr));
                Log.d(TAG, "Encontrado: " + name + " / " + addr);

            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Log.d(TAG, "Discovery finished -> abrir seletor");
                runOnUiThread(() -> showDevicePickerDialog());
            }
        }
    };

    /* =====================================================
     * Ciclo de vida
     * ===================================================== */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // ===== 1. Liga UI =====
        txtDeviceModel      = findViewById(R.id.txtDeviceModel);
        txtPrintMode        = findViewById(R.id.txtPrintMode);
        txtStatus           = findViewById(R.id.txtStatus);
        txtHint             = findViewById(R.id.txtHint);
        btnScan             = findViewById(R.id.btnScan);

        btnPrintAlign       = findViewById(R.id.btnPrintAlign);
        btnPrintImage       = findViewById(R.id.btnPrintImage);
        btnPrintQr          = findViewById(R.id.btnPrintQr);
        btnPrintCode128     = findViewById(R.id.btnPrintCode128);
        btnCut              = findViewById(R.id.btnCut);
        btnPrintSizes       = findViewById(R.id.btnPrintSizes);
        btnPrintRoundedGrid = findViewById(R.id.btnPrintRoundedGrid);
        btnPrintTextRounded = findViewById(R.id.btnPrintTextRounded);

        // ===== 2. Detectar fabricante e backend =====
        deviceManufacturer = detectManufacturer();
        backend = chooseBackend(deviceManufacturer);

        // Atualiza card "Equipamento"
        String fabricanteStr = deviceManufacturer.name();
        String modeloStr     = Build.MODEL == null ? "??" : Build.MODEL;
        txtDeviceModel.setText(
                "Fabricante: " + fabricanteStr + " / Modelo: " + modeloStr
        );

        if (backend == PrintBackend.AIDL) {
            txtPrintMode.setText("Modo de impressão: Serviço interno (AIDL)");
            txtHint.setText("Impressora interna do terminal. Não precisa parear.");
        } else {
            txtPrintMode.setText("Modo de impressão: Bluetooth ESC/POS externo");
            txtHint.setText("Após selecionar uma vez, reconecta sozinho na próxima abertura.");
        }

        // ===== 3. Inicializar de acordo com backend =====
        if (backend == PrintBackend.BLUETOOTH) {
            // Bluetooth Adapter
            btAdapter = BluetoothAdapter.getDefaultAdapter();
            if (btAdapter == null) {
                Toast.makeText(
                        this,
                        "Este dispositivo não tem Bluetooth clássico.",
                        Toast.LENGTH_LONG
                ).show();
            }

            // registrar receiver de discovery
            IntentFilter f = new IntentFilter();
            f.addAction(BluetoothDevice.ACTION_FOUND);
            f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            registerReceiver(discoveryReceiver, f);
            receiverRegistered = true;

            // botão scan visível
            btnScan.setVisibility(View.VISIBLE);
            btnScan.setOnClickListener(v -> startDiscoveryAndSelect());

            // tenta auto-reconectar última impressora salva
            attemptAutoReconnectBluetooth();

        } else {
            // backend == AIDL
            btnScan.setVisibility(View.GONE);
            bindAidlService();
            txtStatus.setText("Status: Conectando serviço interno...");
        }

        // ===== 4. Listeners dos botões de impressão =====
        btnPrintSizes.setOnClickListener(v -> {

            if (backend == PrintBackend.BLUETOOTH) {
                // imprime "grid de bolinhas" via ESC/POS custom
                io.execute(() -> {
                    try {
                        escPosPrinter.beginJob();
                        escPosPrinter.setAlign(1);

                        String[] seq = {
                                "01","02","03","04","05",
                                "06","07","08","09","10",
                                "11","12","13","14","15"
                        };
                        escPosPrinter.printGrid(seq, 5, 24, 22f);

                        escPosPrinter.feed(1);

                        String[] seq2 = {
                                "01","02","03","04","05",
                                "06","07","08","09","10",
                                "11","12"
                        };
                        escPosPrinter.printGrid(seq2, 5, 24, 22f);

                        escPosPrinter.feed(1);
                        escPosPrinter.printGrid(seq2, 4, 24, 22f);

                        escPosPrinter.feed(3);
                        escPosPrinter.endJob();
                    } catch (IOException e) {
                        runOnUiThread(() -> showError(e));
                    }
                });
            } else {
                // AIDL não tem grid fancy pronto -> vamos gerar texto simples com numeração
                io.execute(() -> {
                    try {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 1; i <= 15; i++) {
                            sb.append(String.format("%02d ", i));
                            if (i % 5 == 0) sb.append("\n");
                        }
                        aidlPrinterService.printText(sb.toString(), aidlCallback);
                        aidlPrinterService.printWrapPaper(2, aidlCallback);
                    } catch (Exception e) {
                        runOnUiThread(() -> showError(e));
                    }
                });
            }
        });

        btnPrintRoundedGrid.setOnClickListener(v -> {

            if (backend == PrintBackend.BLUETOOTH) {
                io.execute(() -> {
                    try {
                        escPosPrinter.beginJob();
                        escPosPrinter.setAlign(1);

                        String[] seq = {
                                "01","02","03","04","05",
                                "06","07","08","09","10",
                                "11","12","13","14","15"
                        };

                        escPosPrinter.printRoundedGrid(
                                seq,
                                5,
                                64,
                                48,
                                10,
                                22f
                        );

                        escPosPrinter.feed(3);
                        escPosPrinter.endJob();
                    } catch (IOException e) {
                        runOnUiThread(() -> showError(e));
                    }
                });
            } else {
                // fallback em AIDL -> outra grade textual
                io.execute(() -> {
                    try {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 1; i <= 15; i++) {
                            sb.append("[").append(String.format("%02d", i)).append("] ");
                            if (i % 5 == 0) sb.append("\n");
                        }
                        aidlPrinterService.printText(sb.toString(), aidlCallback);
                        aidlPrinterService.printWrapPaper(2, aidlCallback);
                    } catch (Exception e) {
                        runOnUiThread(() -> showError(e));
                    }
                });
            }
        });

        btnPrintTextRounded.setOnClickListener(v -> {

            final String textoDemo =
                    "Olho em redor do bar em que escrevo estas linhas. " +
                            "Aquele homem ali no balcão, caninha após caninha, " +
                            "nem desconfia que se acha conosco desde o início das eras. " +
                            "Pensa que está somente afogando problemas dele, João Silva... " +
                            "Ele está é bebendo a milenar inquietação do mundo!";

            if (backend == PrintBackend.BLUETOOTH) {
                io.execute(() -> {
                    try {
                        escPosPrinter.beginJob();
                        escPosPrinter.printParagraphInRoundedBox(
                                textoDemo,
                                24,  // tamanho fonte px
                                16,  // padding interno px
                                20   // raio canto px
                        );
                        escPosPrinter.endJob();
                    } catch (IOException e) {
                        runOnUiThread(() -> showError(e));
                    }
                });
            } else {
                io.execute(() -> {
                    try {
                        aidlPrinterService.printText(textoDemo + "\n\n", aidlCallback);
                        aidlPrinterService.printWrapPaper(2, aidlCallback);
                    } catch (Exception e) {
                        runOnUiThread(() -> showError(e));
                    }
                });
            }
        });

        btnPrintAlign.setOnClickListener(v -> {

            if (backend == PrintBackend.BLUETOOTH) {
                io.execute(() -> {
                    try {
                        escPosPrinter.beginJob();
                        escPosPrinter.txtPrint("ALINHADO ESQUERDA (normal)", 0, 0);
                        escPosPrinter.txtPrint("CENTRO 2x", 1, 1);
                        escPosPrinter.txtPrint("DIREITA 3x", 2, 3);
                        escPosPrinter.endJob();
                    } catch (IOException e) {
                        runOnUiThread(() -> showError(e));
                    }
                });
            } else {
                io.execute(() -> {
                    try {
                        String multiline =
                                "ALINHADO ESQUERDA (normal)\n" +
                                        "   CENTRO ~2x (simulado)\n" +
                                        "         DIREITA ~3x (simulado)\n";
                        aidlPrinterService.printText(multiline, aidlCallback);
                        aidlPrinterService.printWrapPaper(2, aidlCallback);
                    } catch (Exception e) {
                        runOnUiThread(() -> showError(e));
                    }
                });
            }
        });

        btnPrintImage.setOnClickListener(v -> {

            if (backend == PrintBackend.BLUETOOTH) {
                io.execute(() -> {
                    try {
                        escPosPrinter.beginJob();
                        escPosPrinter.setAlign(1);
                        escPosPrinter.printImageResource(getResources(), R.drawable.img);
                        escPosPrinter.endJob();
                    } catch (IOException e) {
                        runOnUiThread(() -> showError(e));
                    }
                });
            } else {
                io.execute(() -> {
                    try {
                        Bitmap bmp = BitmapFactory.decodeResource(
                                getResources(),
                                R.drawable.img
                        );
                        aidlPrinterService.printBitmap(bmp, aidlCallback);
                        aidlPrinterService.printWrapPaper(2, aidlCallback);
                    } catch (Exception e) {
                        runOnUiThread(() -> showError(e));
                    }
                });
            }
        });

        btnPrintQr.setOnClickListener(v -> {

            final String qrPayload = "000201010212BR.GOV.BCB.PIX....EXEMPLO";
            if (backend == PrintBackend.BLUETOOTH) {
                io.execute(() -> {
                    try {
                        escPosPrinter.beginJob();
                        escPosPrinter.setAlign(1);
                        escPosPrinter.txtPrint("Pague com PIX:", 1, 1);
                        escPosPrinter.printQrCode(qrPayload, 256);
                        escPosPrinter.endJob();
                    } catch (IOException e) {
                        runOnUiThread(() -> showError(e));
                    }
                });
            } else {
                io.execute(() -> {
                    try {
                        aidlPrinterService.printText("Pague com PIX:\n", aidlCallback);
                        // align=1 (centro?), size=6 (tamanho simbólico)
                        aidlPrinterService.printQRCode(qrPayload, 1, 6, aidlCallback);
                        aidlPrinterService.printWrapPaper(2, aidlCallback);
                    } catch (Exception e) {
                        runOnUiThread(() -> showError(e));
                    }
                });
            }
        });

        btnPrintCode128.setOnClickListener(v -> {

            final String code = "123456789012";
            if (backend == PrintBackend.BLUETOOTH) {
                io.execute(() -> {
                    try {
                        escPosPrinter.beginJob();
                        escPosPrinter.setAlign(1);
                        escPosPrinter.txtPrint("CODIGO DE BARRAS:", 1, 0);
                        escPosPrinter.printCode128(code, 300, 100);
                        escPosPrinter.endJob();
                    } catch (IOException e) {
                        runOnUiThread(() -> showError(e));
                    }
                });
            } else {
                io.execute(() -> {
                    try {
                        aidlPrinterService.printText("CODIGO DE BARRAS:\n", aidlCallback);
                        // align=1 (centro), width/height arbitrários, showContent=true
                        aidlPrinterService.printBarCode(
                                code,
                                1,
                                3,
                                100,
                                true,
                                aidlCallback
                        );
                        aidlPrinterService.printWrapPaper(2, aidlCallback);
                    } catch (Exception e) {
                        runOnUiThread(() -> showError(e));
                    }
                });
            }
        });

        btnCut.setOnClickListener(v -> {

            if (backend == PrintBackend.BLUETOOTH) {
                io.execute(() -> {
                    try {
                        escPosPrinter.feed(3);
                        escPosPrinter.partialCut();
                    } catch (IOException e) {
                        runOnUiThread(() -> showError(e));
                    }
                });
            } else {
                io.execute(() -> {
                    try {
                        aidlPrinterService.printWrapPaper(3, aidlCallback);
                    } catch (Exception e) {
                        runOnUiThread(() -> showError(e));
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (receiverRegistered) {
            unregisterReceiver(discoveryReceiver);
            receiverRegistered = false;
        }

        if (btConn != null) {
            btConn.close();
            btConn = null;
        }

        // desbind do serviço aidl se necessário
        if (aidlBound) {
            try {
                unbindService(aidlConnection);
            } catch (Exception ignored) {}
            aidlBound = false;
            aidlPrinterService = null;
        }

        io.shutdownNow();
    }

    /* =====================================================
     * DETECÇÃO DE FABRICANTE E BACKEND
     * ===================================================== */
    private Manufacturer detectManufacturer() {
        // Normalizamos tudo pra maiúsculo pra facilitar comparação
        String man = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toUpperCase();
        String brand = Build.BRAND == null ? "" : Build.BRAND.toUpperCase();
        String model = Build.MODEL == null ? "" : Build.MODEL.toUpperCase();

        if (man.contains("PAX") || brand.contains("PAX") || model.contains("PAX")) {
            return Manufacturer.PAX;
        }
        if (man.contains("SUNMI") || brand.contains("SUNMI") || model.contains("SUNMI")) {
            return Manufacturer.SUNMI;
        }
        if (man.contains("GERTEC") || brand.contains("GERTEC") || model.contains("GERTEC")) {
            return Manufacturer.GERTEC;
        }
        if (man.contains("POSITIVO") || brand.contains("POSITIVO")) {
            return Manufacturer.POSITIVO;
        }
        // L500 pode ser marca/modelo custom: detecta pelo modelo
        if (model.contains("L500") || brand.contains("L500") || man.contains("L500")) {
            return Manufacturer.L500;
        }

        return Manufacturer.DESCONHECIDO;
    }

    private PrintBackend chooseBackend(Manufacturer m) {
        switch (m) {
            case POSITIVO:
            case L500:
                return PrintBackend.AIDL;

            case PAX:
            case SUNMI:
            case GERTEC:
            default:
                return PrintBackend.BLUETOOTH;
        }
    }

    /* =====================================================
     * AUTO-RECONNECT BLUETOOTH
     * ===================================================== */
    private void attemptAutoReconnectBluetooth() {
        if (backend != PrintBackend.BLUETOOTH) return;

        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final String savedMac = sp.getString(PREF_KEY_MAC, null);
        final String savedName = sp.getString(PREF_KEY_NAME, null);

        if (savedMac == null || savedMac.isEmpty()) {
            txtStatus.setText("Status: Nenhuma impressora selecionada");
            return;
        }

        io.execute(() -> {
            try {
                if (!checkAndRequestBtPermissions()) {
                    runOnUiThread(() ->
                            txtStatus.setText("Status: Permissão BT negada"));
                    return;
                }

                if (btConn != null) {
                    btConn.close();
                    btConn = null;
                }

                if (btAdapter == null || !btAdapter.isEnabled()) {
                    runOnUiThread(() ->
                            txtStatus.setText("Status: Bluetooth desligado"));
                    return;
                }

                btConn = new BluetoothPrinterConnection();
                btConn.connect(btAdapter, savedMac, SPP_UUID);
                escPosPrinter = new BluetoothEscPosPrinter(btConn.getOutputStream());

                runOnUiThread(() -> {
                    txtStatus.setText(
                            "Status: Conectado em " + savedName + " (" + savedMac + ")"
                    );
                    Toast.makeText(
                            MainActivity.this,
                            "Reconectado a " + savedName,
                            Toast.LENGTH_SHORT
                    ).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Falha ao reconectar automática", e);
                runOnUiThread(() -> {
                    txtStatus.setText(
                            "Status: Falha ao reconectar (" + e.getMessage() + ")"
                    );
                    Toast.makeText(
                            MainActivity.this,
                            "Não foi possível reconectar",
                            Toast.LENGTH_SHORT
                    ).show();
                });

                if (btConn != null) {
                    btConn.close();
                    btConn = null;
                }
                escPosPrinter = null;
            }
        });
    }

    /* =====================================================
     * BIND AIDL SERVICE
     * ===================================================== */
    private void bindAidlService() {
        if (backend != PrintBackend.AIDL) return;

        // OBS: você pode precisar ajustar action/package conforme a POS.
        // Aqui usamos um Intent genérico apontando para o pacote do serviço.
        try {
            Intent svcIntent = new Intent();
            // ajuste se sua POS expõe outro action / package:
            svcIntent.setPackage("com.xcheng.printerservice");
            svcIntent.setAction("com.xcheng.printerservice.IPrinterService");

            boolean ok = bindService(svcIntent, aidlConnection, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "bindService AIDL = " + ok);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao bindar serviço AIDL", e);
            txtStatus.setText("Status: Erro ao conectar serviço interno");
        }
    }

    /* =====================================================
     * SCAN BLUETOOTH + DIÁLOGO DE SELEÇÃO
     * ===================================================== */
    @SuppressLint("MissingPermission")
    private void startDiscoveryAndSelect() {
        if (backend != PrintBackend.BLUETOOTH) {
            Toast.makeText(this,
                    "Este terminal usa impressora interna. Não há pareamento.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (!checkAndRequestBtPermissions()) return;

        if (btAdapter == null) {
            Toast.makeText(this, "Sem adaptador Bluetooth.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!btAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
            return;
        }

        Toast.makeText(this, "Buscando dispositivos...", Toast.LENGTH_SHORT).show();

        foundDevices.clear();

        // adiciona pareados primeiro
        Set<BluetoothDevice> bondedSet = btAdapter.getBondedDevices();
        if (bondedSet != null) {
            for (BluetoothDevice bonded : bondedSet) {
                String name = bonded.getName() == null ? "Sem Nome" : bonded.getName();
                String addr = bonded.getAddress();
                foundDevices.add(new PrinterDevice(name, addr));
            }
        }

        // começa discovery; quando terminar chamamos showDevicePickerDialog()
        if (btAdapter.isDiscovering()) {
            btAdapter.cancelDiscovery();
        }
        btAdapter.startDiscovery();
    }

    private void showDevicePickerDialog() {
        if (foundDevices.isEmpty()) {
            Toast.makeText(this, "Nenhum dispositivo encontrado.", Toast.LENGTH_SHORT).show();
            return;
        }

        final String[] labels = new String[foundDevices.size()];
        for (int i = 0; i < foundDevices.size(); i++) {
            PrinterDevice d = foundDevices.get(i);
            labels[i] = d.name + " (" + d.address + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle("Selecione a impressora")
                .setItems(labels, (dialog, which) -> {
                    PrinterDevice chosen = foundDevices.get(which);
                    connectAndSaveBluetooth(chosen);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /* =====================================================
     * CONECTAR / SALVAR (BLUETOOTH)
     * ===================================================== */
    @SuppressLint("MissingPermission")
    private void connectAndSaveBluetooth(PrinterDevice device) {
        if (backend != PrintBackend.BLUETOOTH) return;
        if (device == null) return;
        if (!checkAndRequestBtPermissions()) return;

        Toast.makeText(
                this,
                "Conectando em " + device.name + "...",
                Toast.LENGTH_SHORT
        ).show();

        io.execute(() -> {
            try {
                if (btConn != null) {
                    btConn.close();
                    btConn = null;
                }

                if (btAdapter == null || !btAdapter.isEnabled()) {
                    runOnUiThread(() -> showError(
                            new IOException("Bluetooth desligado")));
                    return;
                }

                btConn = new BluetoothPrinterConnection();
                btConn.connect(btAdapter, device.address, SPP_UUID);

                escPosPrinter = new BluetoothEscPosPrinter(btConn.getOutputStream());

                // salva em SharedPreferences
                SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                sp.edit()
                        .putString(PREF_KEY_MAC, device.address)
                        .putString(PREF_KEY_NAME, device.name)
                        .apply();

                runOnUiThread(() -> {
                    txtStatus.setText(
                            "Status: Conectado em " +
                                    device.name + " (" + device.address + ")"
                    );
                    Toast.makeText(
                            MainActivity.this,
                            "Conectado e salvo!",
                            Toast.LENGTH_SHORT
                    ).show();
                });

            } catch (Exception e) {
                runOnUiThread(() -> showError(e));

                if (btConn != null) {
                    btConn.close();
                    btConn = null;
                }
                escPosPrinter = null;
            }
        });
    }

    /* =====================================================
     * ESTADO DE CONEXÃO / ERRO
     * ===================================================== */
    private boolean checkConnected() {
        if (backend == PrintBackend.BLUETOOTH) {
            if (btConn == null || escPosPrinter == null || !btConn.isConnected()) {
                Toast.makeText(this,
                        "Conecte uma impressora Bluetooth primeiro.",
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        } else {
            if (!aidlBound || aidlPrinterService == null) {
                Toast.makeText(this,
                        "Impressora interna não está pronta.",
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }
    }

    private void showError(Exception e) {
        Log.e(TAG, "ERRO: ", e);
        Toast.makeText(this, "Erro: " + e.getMessage(), Toast.LENGTH_LONG).show();
        txtStatus.setText("Status: ERRO (" + e.getMessage() + ")");
    }

    /* =====================================================
     * PERMISSÕES BLUETOOTH
     * ===================================================== */
    private boolean checkAndRequestBtPermissions() {
        List<String> req = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                req.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                req.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                req.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH)
                    != PackageManager.PERMISSION_GRANTED) {
                req.add(Manifest.permission.BLUETOOTH);
            }
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_ADMIN)
                    != PackageManager.PERMISSION_GRANTED) {
                req.add(Manifest.permission.BLUETOOTH_ADMIN);
            }
        }

        if (!req.isEmpty()) {
            permLauncher.launch(req.toArray(new String[0]));
            return false;
        }

        return true;
    }
}
