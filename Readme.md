# MANUAL DE INTEGRAÇÃO
Impressão ESC/POS via Bluetooth (Android)
Driver: BluetoothEscPosPrinter (custom)


# VISÃO RÁPIDA (cola para o dev apressado)

Fluxo básico:

1. Conectar via Bluetooth SPP (RFCOMM).
2. Criar BluetoothEscPosPrinter passando o OutputStream.
3. Usar beginJob() / endJob() pra imprimir.
4. Chamar métodos de alto nível:

    * txtPrint(texto, alinhamento, escala)
    * printImageResource(...)
    * printQrCode(...)
    * printCode128(...)
    * printGrid(...)
    * printRoundedGrid(...)
    * printParagraphInRoundedBox(...)
    * feed()
    * partialCut()

Uso típico:

```
// depois de conectar:
BluetoothEscPosPrinter printer =
    new BluetoothEscPosPrinter(btConn.getOutputStream());

io.execute(() -> {
    try {
        printer.beginJob();
        printer.txtPrint("OLÁ MUNDO", 1, 1);
        printer.printQrCode("chave pix aqui", 256);
        printer.endJob();
        printer.feed(5);
        printer.partialCut(); // se a guilhotina existir
    } catch (IOException e) {
        // tratar erro
    }
});
```

Pronto. O resto deste manual explica cada parte.

# 1. ARQUITETURA DO PROJETO

O projeto está dividido em 3 responsabilidades:

(1) BluetoothPrinterConnection
- Abre conexão RFCOMM usando o MAC address da impressora.
- Usa o UUID SPP clássico:
  00001101-0000-1000-8000-00805f9b34fb
- Entrega um OutputStream conectado diretamente na impressora.

(2) BluetoothEscPosPrinter
- Classe principal de impressão ESC/POS.
- Recebe o OutputStream no construtor.
- Expõe métodos de alto nível para imprimir texto, imagens, QR code, etc.
- Trata problemas clássicos de impressora 58mm que perde buffer, corta imagem etc.

(3) MainActivity (ou sua Activity/Service)
- Faz scan Bluetooth.
- Mostra lista de impressoras.
- Conecta na selecionada.
- Cria uma instância de BluetoothEscPosPrinter.
- Dispara as impressões em uma thread separada (ExecutorService) para não travar a UI.

IMPORTANTE: toda escrita no Bluetooth deve rodar fora da UI thread.

# 2. FLUXO DE USO NA ACTIVITY

Passo a passo típico no app:

1. Escanear dispositivos Bluetooth:

    * Pede permissões (BLUETOOTH_SCAN / CONNECT em Android 12+).
    * Usa BluetoothAdapter.startDiscovery().
    * Lista pareados + encontrados num RecyclerView.

2. Selecionar um dispositivo e conectar:

    * Pega o MAC address.
    * Abre socket RFCOMM com o UUID SPP.
    * Obtém OutputStream.
    * Cria:
      printer = new BluetoothEscPosPrinter(outStreamObtido);

3. Antes de imprimir:

    * Verifica conexão (socket ainda vivo).

4. Para imprimir:

    * Usa um ExecutorService singleThreadExecutor().
    * Dentro do executor:
      printer.beginJob();
      ... (chama os métodos de impressão) ...
      printer.endJob();
      opcional: printer.feed(5); printer.partialCut();

Por que thread separada?

* Se mandar dados ESC/POS na UI thread, você pode travar a interface (ANR).
* Impressoras lentas + Bluetooth ruim = I/O bloqueante.

# 3. SOBRE A CLASSE BluetoothEscPosPrinter

O que ela faz:

* Comandos ESC/POS básicos (alinhamento, bold, tamanho de fonte, feed, corte).
* Impressão de texto formatado.
* Impressão de imagens (logo, QR, código de barras).
* Impressão de "grades" de números (círculos / caixas arredondadas).
* Impressão de parágrafos dentro de caixas arredondadas (tipo bloco de aviso).

Constantes internas importantes:

* MAX_WIDTH_DOTS = 384
  -> Largura típica da cabeça térmica para impressora 58mm.
  -> Tudo (imagem, QR, grids) é limitado/reescalado para caber nessa largura.

* STRIPE_HEIGHT = 64 (ou 128 em algumas versões)
  -> A imagem nunca é enviada inteira de uma vez.
  -> Ela é dividida em "faixas" horizontais (stripes) pequenas.
  -> Isso evita:

    * buffer overflow interno da impressora,
    * corte da imagem no meio,
    * erro "unknown -2",
    * travamento em POS.

* STRIPE_PAUSE_MS ~ 20ms
  -> Pausa curta entre stripes para deixar a impressora respirar.

Por que isso é importante?
Muitas térmicas baratas (e POS embarcados) NÃO aguentam um bitmap grande num único comando ESC/POS.
Mandar em stripes pequenas resolveu corte pela metade e travamentos.

# 4. CICLO beginJob() / endJob()

printer.beginJob():

* Envia ESC @ (reset ESC/POS).
* Garante um estado "limpo": alinhamento à esquerda, bold desligado, fonte normal.
* Use no começo de TODO BLOCO lógico de impressão (ex: início do cupom).

printer.endJob():

* Dá um pequeno feed final (salta ~2 linhas).
* Reseta estilos para não “vazar” formatação pro próximo cupom.

Recomendação:

* Faça sempre:
  printer.beginJob();
  ...imprime tudo...
  printer.endJob();

Assim você sempre imprime previsível, independente do estado anterior da cabeça.


# 5. IMPRESSÃO DE TEXTO

Assinatura:
txtPrint(String text, int align, int scale)

Parâmetros:

* text  : texto da linha. (o método já adiciona "\n" no final)
* align : 0 = esquerda, 1 = centro, 2 = direita
* scale : 0 = fonte normal
  1 = 2x largura/altura
  3 = 3x largura/altura (se o hardware suportar)
  (internamente vira ESC/POS "GS ! n" com n=0x00,0x11,0x22)

Comportamento interno de txtPrint():

1. setAlign(align)  -> ESC a n
2. setTextSize(byte) -> GS ! n (tamanho)
3. setBold(scale > 0) -> ativa bold se fonte grande
4. escreve texto + "\n"

Por que essa ordem importa?
Algumas impressoras ignoravam alinhamento se você mudasse o tamanho da fonte depois.
Então primeiro alinha, depois seta tamanho. Isso corrigiu o bug de “alinhamento não funciona”.

Exemplo:

```
printer.beginJob();

// texto padrão, alinhado à esquerda
printer.txtPrint("TOTAL A PAGAR: R$ 123,45", 0, 0);

// centralizado, fonte ~2x, bold automático
printer.txtPrint("OBRIGADO PELA PREFERÊNCIA", 1, 1);

// alinhado à direita, ainda maior (~3x)
printer.txtPrint("VOLTE SEMPRE", 2, 3);

printer.endJob();
```

Obs:

* A codificação default é CP437.
  Se acentos saírem tortos, você pode trocar o Charset para ISO-8859-1 dentro da classe.


# 6. FEED E CORTE


feed(int linhas)

* Avança o papel 'linhas' vezes (enviando LF).
* Útil pra “dar espaço” antes do corte.

partialCut()

* Envia GS V 1 (corte parcial).
* Muitas impressoras 58mm portáteis NÃO têm guilhotina -> ignoram esse comando.
* Então se não cortar, é normal.

Uso típico no final de um cupom:

```
printer.feed(5);      // empurra papel pra fora
printer.partialCut(); // tenta cortar, se existir guilhotina
```

# 7. IMPRESSÃO DE IMAGENS, QR E CÓDIGO DE BARRAS

=== 7.1 Imagem comum (logo, cupom fiscal renderizado etc.) ===

Método:
printImageResource(Resources res, int drawableId)

O que faz:

* Carrega um Bitmap do drawable.
* Redimensiona para caber na largura MAX_WIDTH_DOTS (ex: 384 px).
* Converte para preto/branco.
* Divide em stripes de poucas linhas.
* Manda uma stripe por vez via comando ESC/POS raster (GS v 0).
* Pausa entre stripes.
* Resultado: imagem sai COMPLETA, sem corte no meio.

Uso:

```
printer.beginJob();
printer.printImageResource(getResources(), R.drawable.img_logo);
printer.endJob();
```

=== 7.2 QR Code ===

Método:
printQrCode(String data, int sizePx)

* Gera um QRCode usando ZXing.
* Desenha esse QR em Bitmap preto/branco.
* Imprime com o mesmo pipeline seguro de stripes.

Uso:

```
printer.beginJob();
printer.printQrCode("000201010212BR.GOV.BCB.PIX....EXEMPLO", 256);
printer.endJob();
```

sizePx:

* Tamanho em pixels do QR gerado (antes de eventual ajuste de largura).
* 256 geralmente fica bom numa 58mm.

=== 7.3 Código de Barras CODE128 ===

Método:
printCode128(String data, int widthPx, int heightPx)

* Gera um CODE128 (leitor de código de barras padrão de PDV costuma ler).
* widthPx e heightPx controlam o “comprimento” visual final.
* Também vai em stripes, então não corta.

Uso:

```
printer.beginJob();
printer.printCode128("123456789012", 300, 100);
printer.endJob();
```


# 8. GRID DE NÚMEROS (BOLAS / CARTELAS)


Temos dois recursos para loterias, rifas, cartelas, etc.

8.1 printGrid (bolinhas)

Assinatura:
printGrid(
List<String> numbers,
int columns,
int radiusPx,
float textSizePx
)

Ou versões helper:
printGrid(String[] numbers, ...)
printGrid(int[] numbers, ...)

O que faz:

* Gera um bitmap com várias células.
* Cada célula é um círculo desenhado (stroke preto).
* Dentro do círculo, o número é centralizado.
* A fonte tenta ter o tamanho solicitado, mas se não couber no círculo ela é reduzida automaticamente.
* A grade toda é centralizada na impressão e enviada em stripes.

Parâmetros:

* numbers  -> lista de "01","02","03",... ou usa int[] que já formata "%02d".
* columns  -> quantas colunas por linha (ex: 5).
* radiusPx -> raio do círculo (ex: 24 px).
* textSizePx -> tamanho de fonte alvo (ex: 22f).

Exemplo:

```
int[] jogo = { 1,2,3,4,5,6,7,8,9,10,
               11,12,13,14,15 };

printer.beginJob();
printer.printGrid(jogo,
                  5,     // 5 colunas
                  24,    // raio do círculo ~24px
                  22f);  // fonte alvo
printer.endJob();
```

Saída (conceitual):
(01) (02) (03) (04) (05)
(06) (07) (08) (09) (10)
(11) (12) (13) (14) (15)

Visualmente são círculos contornados com os números centralizados.

8.2 printRoundedGrid (caixas arredondadas)

Assinatura:
printRoundedGrid(
List<String> numbers,
int columns,
int boxWidthPx,
int boxHeightPx,
int cornerRadiusPx,
float textSizePxWanted
)

O que faz:

* Cada número fica dentro de um retângulo com cantos arredondados.
* Parecido com uma tabela de aposta / cartela de bingo / cartela de loteria.
* O texto é centralizado na box.
* A fonte também é reduzida se estiver grande demais pra caber.

Parâmetros:

* columns         -> número de colunas.
* boxWidthPx      -> largura da caixa em px (ex: 60).
* boxHeightPx     -> altura da caixa em px (ex: 40).
* cornerRadiusPx  -> raio dos cantos arredondados (ex: 8).
* textSizePxWanted-> tamanho alvo da fonte (ex: 22f).

Exemplo:

```
String[] dezena = {
    "01","02","03","04","05",
    "06","07","08","09","10"
};

printer.beginJob();
printer.printRoundedGrid(
    dezena,
    5,      // colunas
    60,     // largura box px
    40,     // altura box px
    8,      // canto arredondado
    22f     // tamanho de fonte alvo
);
printer.endJob();
```

Resultado visual:
[ 01 ] [ 02 ] [ 03 ] [ 04 ] [ 05 ]
[ 06 ] [ 07 ] [ 08 ] [ 09 ] [ 10 ]

Mas com bordas arredondadas, bonitinho, centralizado.


# 9. PARÁGRAFO COM CAIXA ARREDONDADA (BLOCO DE AVISO)


Função:
printParagraphInRoundedBox(
String bloco,
int fontPx,
int paddingPx,
int radiusPx
)

O que ela faz:

1. Quebra automaticamente o texto longo em várias linhas usando StaticLayout.
   Ou seja, não precisa inserir "\n" manual em cada linha.
2. Mede a altura total desse texto.
3. Desenha um retângulo com cantos arredondados em volta de TODO o parágrafo.
4. Gera um bitmap com fundo branco + borda preta + texto.
5. Imprime esse bitmap (novamente em stripes, então não corta).

Parâmetros:

* bloco      -> Texto grande (pode ser várias frases).
* fontPx     -> Tamanho da fonte em pixels (por exemplo 24).
* paddingPx  -> Espaço interno entre a borda e o texto (por ex. 16).
* radiusPx   -> Raio do canto arredondado (por ex. 20).

Exemplo:

```
String aviso =
    "Olho em redor do bar em que escrevo estas linhas. " +
    "Aquele homem ali no balcão, caninha após caninha, " +
    "nem desconfia que se acha conosco desde o início das eras...";

printer.beginJob();
printer.printParagraphInRoundedBox(
    aviso,
    24,   // fonte 24px
    16,   // padding interno
    20    // raio canto arredondado
);
printer.endJob();
```

Uso típico:

* Mensagens legais tipo “NÃO É DOCUMENTO FISCAL”.
* Observação de garantia.
* Informativo PIX.
* Termos ou mini-contrato impresso.

# 10. BOAS PRÁTICAS E DICAS

(1) SEMPRE imprimir em background thread
- Não faça escrita Bluetooth na UI thread.
- Use ExecutorService.newSingleThreadExecutor().

(2) SEMPRE checar se está conectado
- Antes de imprimir, verifique se o socket Bluetooth ainda está aberto.
- Verifique se printer != null.
  Exemplo:
  if (btConn == null || printer == null || !btConn.isConnected()) {
  // mostrar Toast "Conecte primeiro."
  }

(3) beginJob() / endJob()
- Use beginJob() e endJob() para cada recibo / comprovante / cupom.
- Isso garante reset ESC/POS, estilo coerente, e evita que um cupom herde
  bold/alinhamento do cupom anterior.

(4) Imagens grandes
- Não tente mandar um bitmap gigante inteiro.
- O método interno já divide em stripes (faixas de altura limitada) e envia
  GS v 0 várias vezes com pequenas pausas.
- Isso foi necessário para resolver:
- corte de imagem pela metade
- erro "unknown -2"
- travamentos em POS embarcados

(5) Alinhamento do texto
- Muitas térmicas não aplicam alinhamento corretamente se você muda
  o tamanho da fonte depois.
- O nosso txtPrint() faz na ordem certa:
  setAlign() -> setTextSize() -> setBold() -> print text
  Não mude a ordem.

(6) Corte parcial
- partialCut() chama o comando ESC/POS padrão.
- Se a impressora não tiver guilhotina ou estiver configurada como "manual tear",
  simplesmente nada acontece. Isso é esperado.

(7) Charset / acentuação
- A classe usa CP437 por padrão.
- Se precisar de acentos corretamente em PT-BR, algumas impressoras aceitam bem
  ISO-8859-1 (Latin-1).
- Você pode trocar o Charset lá no construtor para Charset.forName("ISO-8859-1")
  se sua impressora suportar.

# 11. CHECKLIST PARA ADAPTAR EM OUTRO SISTEMA

Para reaproveitar em outro app Android:

[ ] Copiar a classe BluetoothEscPosPrinter.
- Ela depende apenas de OutputStream e ZXing (para QR/CODE128).

[ ] Criar ou adaptar BluetoothPrinterConnection:
- Responsável por:
  . BluetoothAdapter
  . createRfcommSocketToServiceRecord(UUID SPP)
  . socket.connect()
  . getOutputStream()
  . método isConnected()
  . método close()

[ ] Criar UI para:
- Listar impressoras pareadas + descobertas.
- Selecionar uma e conectar.

[ ] Criar ExecutorService single-thread:
ExecutorService io = Executors.newSingleThreadExecutor();

[ ] Nas ações de botão:
io.execute(() -> {
try {
printer.beginJob();
printer.txtPrint(...);
printer.endJob();
} catch (IOException e) {
// tratar erro (usar runOnUiThread pra mexer na UI)
}
});

[ ] Usar ScrollView na tela se você tiver muitos botões de teste (evita overflow de layout em telas pequenas).

[ ] Quando a Activity for destruída:
- unregisterReceiver(discoveryReceiver)
- fechar conexão btConn.close()
- io.shutdownNow()

# 12. RESUMO FINAL

* Você tem uma classe pronta (BluetoothEscPosPrinter) que:
  -> Sabe imprimir texto com alinhamento, tamanho e bold automático.
  -> Sabe imprimir imagem inteira sem cortar, usando stripes.
  -> Gera e imprime QR Code e CODE128.
  -> Consegue montar tabelas/grades de números em bolinhas ou caixas arredondadas.
  -> Consegue desenhar um bloco de texto longo dentro de uma moldura arredondada, tipo aviso, e imprimir isso.
  -> Faz feed e tenta corte.

* Você só precisa:
  -> Um OutputStream de uma conexão Bluetooth SPP estável;
  -> Chamadas aos métodos em uma thread que não seja a UI;
  -> Verificar conexão antes de mandar dados;
  -> Chamar beginJob()/endJob() ao redor de cada impressão lógica.

Com isso você tem um módulo de impressão ESC/POS reutilizável para cupons, comprovantes, comandas, rifas, bilhetes e etc. Sem depender de SDK proprietário da impressora.
