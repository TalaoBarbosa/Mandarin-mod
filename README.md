# Mandarim Craft 说

Mod de Minecraft (Java 1.21.1, Fabric) para aprender chinês jogando: **quando um item novo entra no inventário, você tem alguns segundos para dizer o nome dele em mandarim, com os tons certos.** Acertou, fica com o item. Errou, o item some, você ouve a pronúncia e tenta de novo quando pegar outro.

- O reconhecimento de voz roda **no seu computador, sem internet** (Vosk, modelo chinês).
- Os **tons são conferidos pela curva da voz**: 2º tom que vira 3º, 4º que não cai etc. No resultado o mod **desenha a curva da sua voz** em cada sílaba por cima da forma certa do tom.
- O reconhecedor **leva o contexto em conta**, como quem ouve já esperando a resposta: a palavra que o jogo pediu pesa mais, e ela vale se estiver entre as 3 melhores hipóteses. Com gravações de nativos, 96–98% das palavras ditas certo passam nas sílabas (eram 90–94%). Os tons continuam sendo conferidos com rigor.
- 115 palavras curtas do dia a dia (木头, 石头, 铁, 钻石, 面包, 剑…), não os nomes oficiais longos (橡木原木).
- A pronúncia certa toca com **voz humana** (projeto Shtooka / audio-cmn).

## Instalar

1. Minecraft Java **1.21.1** com o **Fabric Loader** (instalador em fabricmc.net). O **Fabric API** já vem dentro do mod: não precisa instalar à parte.
2. Baixe o `mandarimcraft-….jar` em **Releases → latest** deste repositório e coloque na pasta `mods` (no Windows: `%appdata%\.minecraft\mods`).
3. Abra o jogo. Na primeira vez ele prepara o reconhecimento de voz (uns segundos).
4. O Windows precisa deixar apps de computador usarem o microfone: Configurações → Privacidade → Microfone.

## Como jogar

- Em cada mundo, ative com **`/mandarim comecar`**. O que você já tinha no inventário também vira desafio, mas errar não tira esses itens; só os novos.
- **Palavra nova vem com 汉字 + pinyin com os tons até você acertar.** Errar não tira a dica: pode errar quantas vezes for, o pinyin continua lá. Para fixar outra dica: `/hanyu dica`.
- **Palavra aprendida volta em revisão**, quando você está com o item no inventário: depois de 10 min de jogo, 30 min, 2 h e 6 h (daí em diante a cada 6 h). A revisão vem com menos dica: primeiro o pinyin sem as marcas de tom, depois só o português. Acertou 4 seguidas, a palavra fica **dominada ★**. Errou: a dica volta um passo e você perde **¼ da pilha** (arredondado para cima). Se for um item só (ferramenta, armadura), ele não some na hora: a palavra volta a ser nova e é pedida de novo com o pinyin, aí valendo o item.
- **A cada 20 minutos de jogo vem um "quantos você tem?"** sobre algo que você já aprendeu (uma pilha de 2 ou mais): diga o número com o classificador, ex.: 64 troncos → **六十四根木头** (liùshísì gēn mùtou); o nome no fim é opcional. Antes do classificador o 2 é 两 (两块石头). Usou 个 no lugar do classificador certo, ou 二 no lugar de 两: o mod dá a dica e mais uma chance. Errou: perde **¼ da pilha** (arredondado para cima), não tudo. A dica diminui quando você acerta (frase inteira → só o classificador → nada) e volta um passo quando você erra.
- Revisão e quantidade nunca vêm coladas: entre uma e outra passam pelo menos 10 minutos de jogo.
- **O desafio espera um momento calmo:** não começa enquanto você está levando dano, caindo, na água, dormindo, cavando ou usando um item, com o HUD escondido (F1), nem se você está parado há mais de 30 s (longe do teclado). No alto da tela aparece por que ele está esperando.
- **Dicionário (tecla H):** todas as palavras com o item, 汉字, pinyin e português, o que você já aprendeu e dominou neste mundo; clique numa palavra para ouvir. A tecla **J** toca de novo a última palavra pedida. As teclas mudam em Opções → Controles → Mandarim Craft.
- **Ajustes (`/hanyu ajustes`, ou pelo dicionário):** escolha o microfone vendo o volume ao vivo, calibre a voz e mude as opções sem comando.
- **Passe o mouse num item** no inventário e a dica mostra o nome em chinês com o pinyin (木头 mùtou), menos o do desafio que está aberto. Para tirar: `/hanyu nomes nao`.
- Na primeira fala o mod pede para você ler **três frases curtas**, cada uma com os quatro tons em ordem (`风调雨顺`, `千奇百怪`, `英雄好汉`), e aprende a altura da sua voz pela média delas; se uma destoar (microfone mexido, barulho), ela fica de fora. Leia em voz alta, do jeito que vai falar no jogo. Dá para refazer com `/hanyu calibrar`.
- Errou (ou o mod não entendeu), você ganha mais uma chance na hora; se foi só o **tom**, ele toca a pronúncia antes (`/hanyu chances 0` para tirar). Se o problema for do mod (microfone, erro na análise), ninguém perde nada: ele avisa e pausa.
- Cada tentativa fica registrada no `logs/latest.log` (o que o mod ouviu, os tons e o volume do microfone), para descobrir o que deu errado.

### Comandos do mundo
| comando | o que faz |
|---|---|
| `/mandarim comecar` · `pausar` · `continuar` | liga e desliga neste mundo |
| `/mandarim palavras` | o que você já aprendeu |
| `/mandarim faltam` | o que ainda falta |
| `/mandarim esquecer <palavra\|tudo>` | a palavra volta a ser nova: é pedida de novo, com o pinyin |

### Comandos do seu jogo
| comando | o que faz |
|---|---|
| `/hanyu` | mostra os ajustes |
| `/hanyu ajustes` | tela de ajustes: microfone (com o volume ao vivo), calibrar, opções |
| `/hanyu dicionario` | o dicionário (também na tecla H) |
| `/hanyu teste` | treino: diga o nome de qualquer item, sem perder nada |
| `/hanyu ouvir <palavra>` | toca a pronúncia (ex.: `/hanyu ouvir 钻石` ou `/hanyu ouvir diamante`) |
| `/hanyu tons rigido\|normal\|leve` | quão exigente com os tons |
| `/hanyu tempo <3-20>` | segundos para falar (padrão 7) |
| `/hanyu dica auto\|nenhuma\|hanzi\|pinyin` | dica no desafio (padrão `auto`: pinyin até acertar; veja acima) |
| `/hanyu calibrar` | refaz a medida da sua voz |
| `/hanyu chances <0-2>` | quantas tentativas extras quando erra (padrão 1) |
| `/hanyu nomes sim\|nao` | nome em chinês na dica dos itens (padrão sim) |

## Problemas comuns
- **O mod não me ouve:** abra `/hanyu ajustes`, escolha o microfone e fale: a barra de volume tem que mexer. Sem escolher, o mod usa o microfone **padrão do Windows** (Configurações → Sistema → Som → Entrada), não o que você escolheu no Discord. Confira também Privacidade → Microfone → "Permitir que aplicativos da área de trabalho acessem o microfone". Depois feche e abra o jogo.
- **A barrinha verde quase não mexe:** aumente o volume de entrada do microfone nas mesmas configurações de som.
- **Acerta as sílabas e erra o tom toda hora:** refaça `/hanyu calibrar` (falando alto, como no jogo) e, se precisar, use `/hanyu tons normal` ou `leve`. O log mostra a altura de cada frase da calibração (`[calibração]`).
- **Outro problema:** o `logs/latest.log` tem uma linha `[tentativa]` para cada fala, com o que o mod ouviu.

## Montar
O GitHub Actions monta sozinho a cada envio (baixa o modelo de voz e publica em Releases). Localmente, com Java 21: ponha o `vosk-model-small-cn-0.22.zip` em `src/main/resources/mandarimcraft/model.zip` e rode `./gradlew build` (no Windows, `gradlew.bat build`). Use o wrapper: com outra versão do Gradle o `.jar` pode sair vazio.

Licença MIT. Gravações: Shtooka/audio-cmn (CC BY-SA). Modelo de voz: Vosk (Apache 2.0). Fonte chinesa: recorte da LXGW WenKai GB (SIL Open Font License 1.1), renomeado para "Mandarim Kai" como a licença pede (texto em `licenses/OFL-mandarim-kai.txt` dentro do `.jar`). Fabric API (Apache 2.0) vai dentro do `.jar`. A espera do momento calmo e a fonte vieram da ideia do mod [Learn or Die](https://github.com/Larenlearn/learn-or-die) (MIT).
