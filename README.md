# Mandarim Craft 说

Mod de Minecraft (Java 1.21.1, Fabric) para aprender chinês jogando: **quando um item novo entra no inventário, você tem alguns segundos para dizer o nome dele em mandarim, com os tons certos.** Acertou, fica com o item. Errou, o item some, você ouve a pronúncia e tenta de novo quando pegar outro.

- O reconhecimento de voz roda **no seu computador, sem internet** (Vosk, modelo chinês).
- Os **tons são conferidos pela curva da voz**: 2º tom que vira 3º, 4º que não cai etc. Testado com gravações humanas: ~96% das sílabas certas passam e ~94% das trocas de tom são pegas.
- 115 palavras curtas do dia a dia (木头, 石头, 铁, 钻石, 面包, 剑…), não os nomes oficiais longos (橡木原木).
- A pronúncia certa toca com **voz humana** (projeto Shtooka / audio-cmn).

## Instalar

1. Minecraft Java **1.21.1** com o **Fabric Loader** (instalador em fabricmc.net) e o mod **Fabric API** para 1.21.1.
2. Baixe o `mandarimcraft-….jar` em **Releases → latest** deste repositório e coloque na pasta `mods` (no Windows: `%appdata%\.minecraft\mods`).
3. Abra o jogo. Na primeira vez ele prepara o reconhecimento de voz (uns segundos).
4. O Windows precisa deixar apps de computador usarem o microfone: Configurações → Privacidade → Microfone.

## Como jogar

- Em cada mundo, ative com **`/mandarim comecar`**. O que você já tinha no inventário também vira desafio, mas errar não tira esses itens; só os novos.
- Na primeira fala o mod pede para você ler uma frase (`你好，我是巴西人。`) e aprende a altura da sua voz. Dá para refazer com `/hanyu calibrar`.
- Quando só o **tom** sai errado, você ganha mais uma chance na hora (`/hanyu chances 0` para tirar).

### Comandos do mundo
| comando | o que faz |
|---|---|
| `/mandarim comecar` · `pausar` · `continuar` | liga e desliga neste mundo |
| `/mandarim palavras` | o que você já aprendeu |
| `/mandarim faltam` | o que ainda falta |
| `/mandarim esquecer <palavra\|tudo>` | volta a pedir a palavra |

### Comandos do seu jogo
| comando | o que faz |
|---|---|
| `/hanyu` | mostra os ajustes |
| `/hanyu teste` | treino: diga o nome de qualquer item, sem perder nada |
| `/hanyu ouvir <palavra>` | toca a pronúncia (ex.: `/hanyu ouvir 钻石` ou `/hanyu ouvir diamante`) |
| `/hanyu tons rigido\|normal\|leve` | quão exigente com os tons |
| `/hanyu tempo <3-20>` | segundos para falar (padrão 7) |
| `/hanyu dica nenhuma\|hanzi\|pinyin` | mostrar dica no desafio |
| `/hanyu calibrar` | refaz a medida da sua voz |

## Montar
O GitHub Actions monta sozinho a cada envio (baixa o modelo de voz e publica em Releases). Localmente: `gradle build` com Java 21, depois de pôr o `vosk-model-small-cn-0.22.zip` em `src/main/resources/mandarimcraft/model.zip`.

Licença MIT. Gravações: Shtooka/audio-cmn (CC BY-SA). Modelo de voz: Vosk (Apache 2.0).
