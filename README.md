# Tradutor Pop-up (Android)

App simples que traduz **em tempo real** textos em **inglês, japonês, espanhol, chinês e russo**
para o português (ou entre esses idiomas), em **qualquer site ou app**, por meio de um botão
flutuante e de um quadro que você move e redimensiona com o dedo.

A tradução aparece **dentro do quadro, por cima do texto original**, em placas de vidro fosco
no estilo dos widgets do iPhone — parecido com o Google Lens.

## Funções

- **Botão flutuante 文A** sobre qualquer app; toque para abrir/fechar o quadro.
- **Quadro redimensionável**: arraste a barra de cima para mover e a bolinha do canto para mudar o tamanho.
  Os toques passam através do quadro, então dá para rolar a página normalmente.
- **Tradução por cima do original**, com a letra ajustada para caber; ao rolar a página, a tradução acompanha.
- **🌐 Detecção automática do idioma** (ou escolha fixa). Um toque rápido na barra troca o idioma.
- **Mangá, quadrinhos e imagens**: quando o quadro está sobre uma imagem, o app lê o texto de dentro dela (OCR).
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

## Limitações

- Só **Android** — o iPhone não permite que apps desenhem por cima de outros apps.
- A tradução offline é mais literal que tradutores online; use as **correções** para ajustar expressões.
