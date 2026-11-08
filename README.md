# Tradutor Pop-up (Android e Windows)

App simples que traduz **em tempo real** textos em **inglês, japonês, espanhol, chinês e russo**
para o português (ou entre esses idiomas), em **qualquer site ou app**, por meio de um botão
flutuante e de um quadro que você move e redimensiona com o dedo (ou o mouse, no PC).

- 📱 **Android**: pasta raiz deste repositório (Kotlin). Instalador `.apk` nas **Releases**.
- 💻 **Windows**: pasta [`windows/`](windows/) (Python). Programa `.zip` nas **Releases** — veja
  [Versão para Windows](#versão-para-windows).

A tradução aparece **dentro do quadro, por cima do texto original**, em placas de vidro fosco
no estilo dos widgets do iPhone — parecido com o Google Lens.

> ⚠️ **A tradução não é 100% correta.** O app usa apenas ferramentas **gratuitas** que rodam
> no próprio celular (Google ML Kit, offline). Elas são mais literais que tradutores pagos ou com IA,
> podem errar expressões e gírias (ex.: "bow and arrow" → "arcos e setas") e, em imagens/mangá,
> o leitor de texto às vezes troca letras. Use para **entender o sentido** do texto; para ajustar
> expressões que se repetem, use as **Correções de tradução** do app.

## Funções

- **Botão flutuante 文A** sobre qualquer app; toque para abrir/fechar o quadro.
- **Quadro redimensionável**: arraste a barra de cima para mover e a bolinha do canto para mudar o tamanho.
  Os toques passam através do quadro, então dá para rolar a página normalmente.
- **Tradução por cima do original**, com a letra ajustada para caber; ao rolar a página, a tradução acompanha.
- **🌐 Detecção automática do idioma** (ou escolha fixa). Um toque rápido na barra troca o idioma.
- **Mangá, quadrinhos e imagens**: quando o quadro está sobre uma imagem, o app lê o texto de dentro dela (OCR),
  juntando as linhas de cada balão (sem misturar balões vizinhos) e remontando palavras quebradas com hífen.
- **Correções de tradução** editáveis no app (`errado = certo`), ex.: `arcos e setas = arco e flecha`.
- **Apps de banco**: o pop-up some sozinho em apps de banco/pagamento. Bancos como o Nubank bloqueiam
  apps de acessibilidade ligados — desligue o tradutor antes (toque e segure o 文A, ou use a chave do app /
  o atalho dos dois botões de volume).
- **Funciona offline** depois de baixar cada idioma uma vez. O texto da tela não é enviado para lugar nenhum.

## Como instalar

### Pelo APK
Baixe o `.apk` na página de **Releases** deste repositório, abra no celular e permita
"instalar de fontes desconhecidas".

### Pelo código (Android Studio)
1. Instale o **Android Studio**: https://developer.android.com/studio
2. **File › Open** e escolha esta pasta. Espere o "Gradle sync" terminar.
3. No celular, ative **Opções do desenvolvedor › Depuração USB**, conecte no PC e clique em ▶ **Run**.

Pela linha de comando: `./gradlew assembleDebug` (o APK sai em `app/build/outputs/apk/debug/`).

## Como usar

1. Abra o app e escolha o **idioma do texto** (ou deixe no **Automático**) e o idioma de destino (Português).
2. Ligue **Ativar pop-up**. Na primeira vez, ative o "Tradutor Pop-up" em
   **Configurações › Acessibilidade › Aplicativos instalados**.
   - Se aparecer "configuração restrita": Configurações › Apps › Tradutor Pop-up › ⋮ › **Permitir configurações restritas**.
3. Em qualquer site ou app, toque no botão **文A** e posicione o quadro sobre o texto.

## Como funciona (técnico)

- **Leitura do texto**: `AccessibilityService` lê os textos da tela dentro do quadro (todos os idiomas).
- **Imagens**: captura só a janela do app de baixo (`takeScreenshotOfWindow`, Android 14+) e usa o
  **ML Kit Text Recognition** (latim, japonês, chinês — o alfabeto russo não é suportado em imagens).
- **Tradução**: **ML Kit Translate** no próprio aparelho; **ML Kit Language ID** para o modo automático.
- **Visual**: fundo desfocado do próprio app de baixo + véu translúcido, borda clara e sombra.

Código em Kotlin, sem bibliotecas de interface externas. Android 8.0+ (efeito de vidro e OCR completos no Android 14+).

## Versão para Windows

Funciona igual ao celular: botão flutuante **文A** sempre por cima, quadro transparente que você
arrasta e redimensiona com o mouse, e a tradução em vidro fosco por cima do texto, em tempo real.

**Instalar:** baixe `TradutorPopup-Windows.zip` nas **Releases**, descompacte em qualquer pasta e
abra `TradutorPopup.exe`. (Windows 10 versão 2004 ou mais novo / Windows 11.)

**Usar:**
- Clique no **文A** ou aperte **Ctrl+Shift+T** para abrir/fechar o quadro.
- Arraste a **barra de cima** para mover e a **bolinha do canto** para mudar o tamanho.
  O mouse passa **através** do quadro — dá para rolar a página normalmente.
- Na barra: clique no **idioma** para trocar (AUTO, EN, JA, ES, ZH, RU), **⚙** abre as configurações, **✕** fecha.
- Botão direito no 文A ou no ícone perto do relógio: menu com Configurações e Sair.

**Como funciona:**
- Lê a tela com o **leitor de texto do próprio Windows** (Windows.Media.Ocr). As janelas do tradutor
  ficam fora da captura, então ele lê só o que está embaixo.
- Agrupa as linhas por **balão/parágrafo** (sem misturar balões vizinhos e remontando palavras com hífen).
- Traduz **offline e grátis** com os modelos do **Argos Translate** (CTranslate2). Cada idioma é baixado
  uma vez (~60–90 MB); japonês, chinês e russo passam pelo inglês.
- **Japonês, chinês e russo** precisam do leitor desse idioma no Windows: em ⚙ Configurações há um
  botão **Instalar** (o Windows pede permissão de administrador).

**Gerar o programa a partir do código:**
```powershell
cd windows
powershell -ExecutionPolicy Bypass -File build.ps1
```
Para rodar sem gerar o .exe: `pip install -r windows/requirements.txt` e `python windows/run.py`.

## Limitações

- **A tradução não é 100% correta**: o app usa só ferramentas gratuitas e offline (Google ML Kit no
  Android, Argos Translate e o leitor do Windows no PC), mais literais que tradutores pagos/com IA. Erros comuns: expressões e gírias traduzidas ao pé da letra,
  letras trocadas na leitura de imagens (ex.: "Garnation" em vez de "carnation"), frases curtas fora de contexto.
  Use as **correções** para ajustar expressões.
- Só **Android** — o iPhone não permite que apps desenhem por cima de outros apps.
- Apps de banco (ex.: Nubank) bloqueiam enquanto o tradutor está ligado — desligue antes de abrir o banco.
