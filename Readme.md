# BluetoothUniversalPrinter
Impressão térmica universal para Android (ESC/POS via Bluetooth ou serviço interno AIDL)

Este projeto padroniza impressão térmica independente do modelo do terminal POS
ou da impressora conectada.  
A ideia é: **mesmo código Java → mesmo layout impresso**, tanto em maquininhas
com impressora interna quanto em impressoras Bluetooth 58mm baratas.

---

## ⚡ Visão Rápida (cola pro dev apressado)

Fluxo normal de impressão:

1. O app detecta o fabricante do terminal.
2. Ele decide o backend:
    - **Bluetooth ESC/POS** (PAX / SUNMI / GERTEC / genérico).
    - **AIDL interno** (POSITIVO / L500).
3. Se for Bluetooth:
    - Faz scan.
    - Usuário escolhe a impressora.
    - Conecta via RFCOMM (UUID SPP clássico).
    - Cria `BluetoothEscPosPrinter`.
4. Se for AIDL:
    - Faz `bindService` no serviço interno do fabricante.
    - Usa `IPrinterService` + `AidlGraphicsPrinter`.

Depois disso, você só chama as funções de alto nível, por exemplo:

```java
io.execute(() -> {
    try {
        // BLUETOOTH
        escPosPrinter.beginJob();
        escPosPrinter.txtPrint("ALINHADO ESQUERDA (normal)", 0, 0);
        escPosPrinter.txtPrint("CENTRO 2x", 1, 1);
        escPosPrinter.txtPrint("DIREITA 3x", 2, 3);
        escPosPrinter.printQrCode("000201...PIX...", 256);
        escPosPrinter.endJob();
        escPosPrinter.feed(3);
        escPosPrinter.partialCut();
    } catch (IOException e) {
        // tratar erro
    }
});
````

> Todas as chamadas de impressão rodam **fora da UI thread** usando um `ExecutorService`
> (`io.execute(...)`).
> Se você fizer I/O Bluetooth na UI thread você arrisca ANR.

---

## 🧠 O que esse projeto resolve

* Impressoras diferentes imprimindo com layouts diferentes → resolvido.
* Impressora Bluetooth externa x impressora interna da maquininha → mesma API.
* Problema clássico da imagem "cortada no meio" → resolvido enviando em faixas.
* Impressão centralizada, caixas arredondadas, QR PIX, código de barras, etc.
* Seleção da impressora pelo próprio app (scan + lista).

---

## 📦 Estrutura lógica

### 1. `MainActivity`

* Faz todo o fluxo de uso real:

    * Detecta o fabricante (`detectManufacturer()`).
    * Decide se vai usar **Bluetooth** ou **AIDL** (`chooseBackend()`).
    * Cuida de permissões, scan e conexão.
    * Salva a impressora escolhida (SharedPreferences).
    * Expõe botões de teste / exemplo de cada tipo de impressão.

### 2. `BluetoothPrinterConnection`

* Abre um socket RFCOMM com a impressora.
* Usa o UUID Serial Port Profile (SPP):
  `00001101-0000-1000-8000-00805f9b34fb`
* Entrega `OutputStream` pronto pra mandar comandos ESC/POS.
* Mantém estado `isConnected()`, e fecha conexão no `onDestroy()`.

### 3. `BluetoothEscPosPrinter`

* Driver ESC/POS de alto nível.
* Possui métodos prontos tipo:

    * `beginJob()`, `endJob()`
    * `txtPrint(...)`
    * `printImageResource(...)`
    * `printQrCode(...)`
    * `printCode128(...)`
    * `printGrid(...)`
    * `printRoundedGrid(...)`
    * `printParagraphInRoundedBox(...)`
    * `feed(...)`
    * `partialCut()`
* Garante compatibilidade com impressoras térmicas 58mm.

### 4. `IPrinterService` + `AidlGraphicsPrinter`

* Usado quando o terminal tem **impressora interna** (ex.: POSITIVO / L500).
* `bindService()` conecta no serviço AIDL do fabricante.
* `aidlPrinterService` fornece:

    * `printText(...)`
    * `printBitmap(...)`
    * `printQRCode(...)`
    * `printBarCode(...)`
    * `printWrapPaper(...)` (avanço de papel)
* `AidlGraphicsPrinter` desenha layouts mais complexos (grade, caixas arredondadas, fontes personalizadas) e manda como bitmap para o serviço.

---

## 🔍 Detecção de fabricante → backend

```java
private enum Manufacturer {
    PAX, SUNMI, GERTEC, POSITIVO, L500, DESCONHECIDO
}

private enum PrintBackend {
    BLUETOOTH, AIDL
}

private Manufacturer detectManufacturer() {
    String man = Build.MANUFACTURER.toUpperCase();
    String brand = Build.BRAND.toUpperCase();
    String model = Build.MODEL.toUpperCase();

    if (man.contains("PAX")     || brand.contains("PAX")     || model.contains("PAX"))     return Manufacturer.PAX;
    if (man.contains("SUNMI")   || brand.contains("SUNMI")   || model.contains("SUNMI"))   return Manufacturer.SUNMI;
    if (man.contains("GERTEC")  || brand.contains("GERTEC")  || model.contains("GERTEC"))  return Manufacturer.GERTEC;
    if (man.contains("POSITIVO")|| brand.contains("POSITIVO"))                            return Manufacturer.POSITIVO;
    if (model.contains("L500")  || brand.contains("L500")    || man.contains("L500"))      return Manufacturer.L500;

    return Manufacturer.DESCONHECIDO;
}

private PrintBackend chooseBackend(Manufacturer m) {
    switch (m) {
        case POSITIVO:
        case L500:
            return PrintBackend.AIDL;       // usa impressora interna
        case PAX:
        case SUNMI:
        case GERTEC:
        default:
            return PrintBackend.BLUETOOTH;  // usa ESC/POS Bluetooth
    }
}
```

Isso acontece logo no `onCreate()`. A UI já mostra para o usuário qual modo foi escolhido:

* “Serviço interno (AIDL)” ou
* “Bluetooth ESC/POS externo”.

---

## 📡 Fluxo Bluetooth

### Permissões

No Android 12+:

* `BLUETOOTH_SCAN`
* `BLUETOOTH_CONNECT`

No Android ≤ 11:

* `ACCESS_FINE_LOCATION`
* `BLUETOOTH`
* `BLUETOOTH_ADMIN`

O código pede em tempo de execução com `ActivityResultLauncher`, e só continua se todas forem aceitas.

### Descoberta e seleção de impressora

```java
btnScan.setOnClickListener(v -> startDiscoveryAndSelect());
```

`startDiscoveryAndSelect()` faz:

1. Garante permissões.
2. Garante que o Bluetooth está ligado.
3. Limpa a lista `foundDevices`.
4. Adiciona impressoras já pareadas (`getBondedDevices()`).
5. Mostra imediatamente um diálogo se já existir algo pareado.
6. Inicia `BluetoothAdapter.startDiscovery()` para achar novos devices.
7. O `BroadcastReceiver` (`discoveryReceiver`) escuta:

    * `ACTION_FOUND` → adiciona cada device descoberto.
    * `ACTION_DISCOVERY_FINISHED` → chama `showDevicePickerDialog()`.

A tela de seleção é um `AlertDialog` com nome + MAC:

```java
new AlertDialog.Builder(this)
    .setTitle("Selecione a impressora")
    .setItems(labels, (dialog, which) -> {
        PrinterDevice chosen = foundDevices.get(which);
        connectAndSaveBluetooth(chosen);
    })
    .setNegativeButton("Cancelar", null)
    .show();
```

### Conexão e persistência

```java
private void connectAndSaveBluetooth(PrinterDevice device) {
    io.execute(() -> {
        try {
            // abre RFCOMM SPP
            btConn = new BluetoothPrinterConnection();
            btConn.connect(btAdapter, device.address, SPP_UUID);

            // cria driver ESC/POS com o OutputStream do socket
            escPosPrinter = new BluetoothEscPosPrinter(btConn.getOutputStream());

            // salva MAC / nome pra reconectar sozinho depois
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            sp.edit()
              .putString(PREF_KEY_MAC, device.address)
              .putString(PREF_KEY_NAME, device.name)
              .apply();

            runOnUiThread(() -> {
                txtStatus.setText("Status: Conectado em " + device.name + " (" + device.address + ")");
                Toast.makeText(MainActivity.this,"Conectado e salvo!",Toast.LENGTH_SHORT).show();
            });

        } catch (Exception e) {
            // erro de conexão
        }
    });
}
```

Na próxima vez que o app abrir, ele tenta `attemptAutoReconnectBluetooth()` usando os dados salvos.

---

## 🔌 Fluxo AIDL (impressora interna POS)

Para habilitar o aidl em seu codigo siga os passos:

1. Copie a pasta `aidl` deste projeto para dentro do seu módulo Android (normalmente `app/src/main/aidl`).

2. Abra o `build.gradle.kts` **do módulo** (ex.: `app/build.gradle.kts`) e adicione as configurações abaixo dentro do bloco `android { ... }`:

```kotlin
android {
    // ... seu conteúdo atual

    sourceSets {
        getByName("main") {
            aidl.srcDirs("src/main/aidl")
        }
    }

    buildFeatures {
        aidl = true
    }
}
```

3. Sincronize o Gradle.

4. Se o Android Studio ainda não reconhecer as interfaces AIDL:

    * Vá em **File > Invalidate Caches...**
    * Escolha **Invalidate and Restart**
    * Depois faça um **Rebuild Project**

Isso força o Android Studio a indexar os `.aidl` e gerar os stubs corretamente.

Quando o backend for `AIDL`, o app:

1. Faz `bindService()` num serviço do fabricante:

   ```java
   Intent svcIntent = new Intent();
   svcIntent.setPackage("com.xcheng.printerservice");
   svcIntent.setAction("com.xcheng.printerservice.IPrinterService");
   bindService(svcIntent, aidlConnection, Context.BIND_AUTO_CREATE);
   ```
2. Recebe uma instância de `IPrinterService` em `onServiceConnected`.
3. Inicializa a impressora:

   ```java
   aidlPrinterService.printerInit(aidlCallback);
   aidlPrinterService.printerReset(aidlCallback);
   aidlReady = true;
   aidlGraphicsPrinter = new AidlGraphicsPrinter(aidlPrinterService, aidlCallback);
   ```
4. Usa `aidlPrinterService` e/ou `aidlGraphicsPrinter` para imprimir texto, bitmap, QR, etc.

Esse modo não precisa Bluetooth, nem pareamento, nem seleção manual.

---

## 🖨️ Funções de impressão disponíveis

A Activity já demonstra cada tipo de impressão em botões.
Abaixo está o que cada botão faz.

### 1. Texto alinhado, tamanhos diferentes

```java
escPosPrinter.beginJob();
escPosPrinter.txtPrint("ALINHADO ESQUERDA (normal)", 0, 0);
escPosPrinter.txtPrint("CENTRO 2x", 1, 1);
escPosPrinter.txtPrint("DIREITA 3x", 2, 3);
escPosPrinter.endJob();
```

* `txtPrint(String text, int align, int scale)`

    * `align`: `0 = esquerda`, `1 = centro`, `2 = direita`
    * `scale`: `0 = normal`, `1 = ~2x`, `3 = ~3x` (se suportado pelo hardware)

Versão AIDL:

```java
String multiline =
    "ALINHADO ESQUERDA (normal)\n" +
    "   CENTRO ~2x (simulado)\n" +
    "         DIREITA ~3x (simulado)\n";
aidlPrinterService.printText(multiline, aidlCallback);
aidlPrinterService.printWrapPaper(2, aidlCallback);
```

---

### 2. Impressão de imagem / logo

```java
escPosPrinter.beginJob();
escPosPrinter.setAlign(1); // centraliza
escPosPrinter.printImageResource(getResources(), R.drawable.img);
escPosPrinter.endJob();
```

* Converte Bitmap em preto/branco.
* Redimensiona para caber na largura da cabeça térmica.
* Envia em “faixas” (stripes) para não cortar no meio e não travar.

Versão AIDL:

```java
Bitmap bmp = BitmapFactory.decodeResource(getResources(), R.drawable.img);
aidlPrinterService.printBitmap(bmp, aidlCallback);
aidlPrinterService.printWrapPaper(2, aidlCallback);
```

---

### 3. QR Code (PIX, etc)

```java
final String qrPayload = "000201010212BR.GOV.BCB.PIX....EXEMPLO";

escPosPrinter.beginJob();
escPosPrinter.setAlign(1);
escPosPrinter.txtPrint("Pague com PIX:", 1, 1);
escPosPrinter.printQrCode(qrPayload, 256); // 256px alvo
escPosPrinter.endJob();
```

Versão AIDL:

```java
aidlPrinterService.printText("Pague com PIX:\n", aidlCallback);
// align=1, size=300 px, etc.
aidlPrinterService.printQRCode(qrPayload, 1, 300, aidlCallback);
aidlPrinterService.printWrapPaper(2, aidlCallback);
```

---

### 4. Código de Barras CODE128

```java
final String code = "123456789012";

escPosPrinter.beginJob();
escPosPrinter.setAlign(1);
escPosPrinter.txtPrint("CODIGO DE BARRAS:", 1, 1);
escPosPrinter.printCode128(code, 300, 100); // largura/altura
escPosPrinter.endJob();
```

Versão AIDL:

```java
aidlPrinterService.printText("CODIGO DE BARRAS:\n", aidlCallback);
aidlPrinterService.printBarCode(
    code,
    1,      // align (1 = centro)
    3,      // barWidth (fino/grosso)
    100,    // height
    true,   // print human-readable content
    aidlCallback
);
aidlPrinterService.printWrapPaper(2, aidlCallback);
```

---

### 5. Grade de números (bolinhas)

```java
String[] seq = {
    "01","02","03","04","05",
    "06","07","08","09","10",
    "11","12","13","14","15"
};

escPosPrinter.beginJob();
escPosPrinter.setAlign(1); // centro
escPosPrinter.printGrid(
    seq,
    5,    // colunas
    24,   // raio aproximado do círculo px
    22f   // tamanho de fonte alvo px
);
escPosPrinter.endJob();
```

No backend AIDL usamos `aidlGraphicsPrinter.printCircleGrid(...)` com a mesma ideia:
desenha o layout em bitmap e manda para a impressora interna.

Essa função é perfeita pra cartelas, rifas, apostas numéricas etc.

---

### 6. Grade de caixas arredondadas

```java
String[] seq = {
    "01","02","03","04","05",
    "06","07","08","09","10",
    "11","12","13","14","15"
};

escPosPrinter.beginJob();
escPosPrinter.setAlign(1);
escPosPrinter.printRoundedGrid(
    seq,
    5,     // colunas
    64,    // largura da box px
    48,    // altura da box px
    10,    // raio canto arredondado px
    22f    // tamanho da fonte alvo px
);
escPosPrinter.endJob();
```

Em AIDL: `aidlGraphicsPrinter.printRoundedGrid(...)`.
Visualmente fica tipo uma tabela de dezenas, com cada célula tendo borda arredondada.

---

### 7. Parágrafo com caixa arredondada

```java
final String textoDemo =
    "Olho em redor do bar em que escrevo estas linhas. " +
    "Aquele homem ali no balcão, caninha após caninha, " +
    "nem desconfia que se acha conosco desde o início das eras...";

escPosPrinter.beginJob();
escPosPrinter.printParagraphInRoundedBox(
    textoDemo,
    24,  // tamanho da fonte em px
    16,  // padding interno em px
    20   // raio do canto arredondado px
);
escPosPrinter.endJob();
```

Em AIDL: `aidlGraphicsPrinter.printParagraphInRoundedBox(...)`.

Essa função:

* Quebra o texto automaticamente em múltiplas linhas.
* Desenha um retângulo com cantos arredondados em volta de TODO o bloco.
* Imprime isso como imagem (faixas seguras).

Ótimo pra:

* Aviso "NÃO É DOCUMENTO FISCAL"
* Termos rápidos
* Mensagem PIX/recibo

---

### 8. Fontes personalizadas (OTF/TTF em `/assets`)

```java
escPosPrinter.printCustomFontText(
    MainActivity.this,
    "😎\nLinha 2\nLinha 3",
    "VarsityTeamBold.otf", // arquivo em assets/
    60f,                   // tamanho da fonte em px
    1,                     // alinhamento: 0=esq,1=centro,2=dir
    1                      // padding em px
);

// outras combinações de fonte/tamanho/alinhamento:
escPosPrinter.printCustomFontText(
    MainActivity.this,
    "Texto com Transcity 😎\nLinha 2\nLinha 3",
    "Transcity.otf",
    18f,
    1,
    1
);
```

Em AIDL: `aidlGraphicsPrinter.printCustomFontText(...)` faz o mesmo conceito.
Internamente a função:

* Renderiza o texto com uma `Typeface` carregada do `assets/`.
* Converte para bitmap preto/branco dentro da largura de impressão.
* Manda esse bitmap pra impressora (em faixas, de novo).

Isso permite layout muito mais bonito e padronizado entre dispositivos.

---

### 9. Feed e corte

```java
escPosPrinter.feed(3);     // avança 3 linhas
escPosPrinter.partialCut(); // tenta corte parcial (se a guilhotina existir)
```

No backend AIDL:

```java
aidlPrinterService.printWrapPaper(3, aidlCallback); // alimenta papel
// corte físico depende do hardware interno; nem todo POS corta
```

**Importante:** impressoras 58mm portáteis geralmente NÃO têm guilhotina.
Se nada cortar, é comportamento esperado.

---

## 🧯 Boas práticas

* **Sempre imprimir em background thread**
  (O projeto usa `ExecutorService io = Executors.newSingleThreadExecutor()`.)

* **Sempre verificar conexão antes de imprimir**

  ```java
  if (!checkConnected()) {
      Toast.makeText(this, "Conecte uma impressora primeiro.", Toast.LENGTH_SHORT).show();
      return;
  }
  ```

* **Sempre fechar recursos no `onDestroy()`**

  ```java
  unregisterReceiver(discoveryReceiver);
  btConn.close();
  io.shutdownNow();
  unbindService(aidlConnection);
  ```

* **Usar `beginJob()` / `endJob()`**
  Cada comprovante/cupom deve começar com `beginJob()` e terminar com `endJob()`.
  Isso garante reset de formatação ESC/POS, alinhamento previsível e espaçamento final.

* **Imagens grandes**
  O driver já fatia imagens/QR/barras em tiras ("stripes").
  Isso evita:

    * imagem sair cortada no meio
    * erro `unknown -2`
    * travamento da impressora

---

## ✅ Checklist para portar pro seu app

* [ ] Copiar `BluetoothPrinterConnection` e `BluetoothEscPosPrinter`.
* [ ] Criar uma Activity / Service que:

    * Pede permissões de Bluetooth.
    * Faz scan (`startDiscoveryAndSelect()`).
    * Mostra lista (`showDevicePickerDialog()`).
    * Salva o MAC address escolhido.
    * Mantém `BluetoothEscPosPrinter` ativo.
* [ ] Se estiver rodando num POS com impressora interna:

    * Fazer `bindService()` no `IPrinterService` do fabricante.
    * Usar `aidlPrinterService` + `AidlGraphicsPrinter` em vez do Bluetooth.
* [ ] Rodar TODA impressão em background thread (`io.execute(...)`).
* [ ] Chamar `beginJob()` / `endJob()` em cada bloco de impressão.
* [ ] Usar `printCustomFontText(...)` para ter mesma estética em qualquer hardware.

---

## 🏁 Resumindo

* O app escolhe automaticamente entre:

    * **Bluetooth ESC/POS** externo, ou
    * **Impressora interna AIDL**.
* Você ganha uma API única de impressão:

    * Texto alinhado/tamanho variável.
    * QRCode PIX.
    * Código de barras.
    * Imagem (logo).
    * Grades numéricas (círculos / caixas arredondadas).
    * Bloco de texto com borda arredondada.
    * Fontes personalizadas (OTF/TTF nos assets).
    * Avanço e corte.
* Tudo isso pensado pra **rodar estável em campo**, sem depender de SDK fechado de cada fabricante.

Use este repositório como base para todos os seus recibos, comprovantes, comandas e bilhetes impressos no Android 🚀
