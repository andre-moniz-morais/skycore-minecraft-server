# Design Specification — Servidor Skyblock

Nov 16, 2026 · @Andre

## Visão e princípios de design

O servidor é um skyblock em que o jogador tem sempre um próximo objetivo visível e nunca é recompensado por ficar AFK. O ritmo vem de três camadas: skills, ilha e tiers de progressão.

**Loop do jogador:** ação ativa → XP + Roses (a cada N ações) → upgrades, pets e ilhas → mais eficiência e conteúdo novo → ações mais exigentes.

**Regras de ouro** (qualquer decisão de balanceamento deve respeitá-las):

1. **Roses = esforço ativo.** Só se ganham por ações do próprio jogador (minerar, pescar, matar, colher) e por missões. Farms, minions, spawners e sell chests nunca dão Roses.
2. **Dollars = abundância.** Vêm da venda de itens, da automação e de missões. Não se convertem em Roses, nem em sentido nenhum nem por troca entre jogadores.
3. **Automação ajuda, não substitui.** Produz Dollars e itens, nunca XP de skill nem Roses.
4. **Tudo tem limite**, e o limite sobe com upgrades da ilha.
5. **Sem pay-to-win.** Nada se compra com dinheiro real; o projeto não tem fins lucrativos.
6. **Performance é requisito de base**, não uma otimização final.
7. **Há sempre um próximo objetivo**: Path de Tiers, missões diárias, missões de ilha e ranking semanal.

## Stack técnica e performance

A base é **Paper** (ou **Leaf**, um fork de Paper com compatibilidade de plugins equivalente) com Java na versão que o Paper exigir. Folia fica de fora: pede cerca de 16 cores e quase nenhum plugin o suporta ([GameServerKings](https://www.gameserverkings.com/knowledge-base/minecraft/start-a-skyblock-server/)). Tudo o que é específico deste design (Roses, skills, minions, sell chests, etc.) vai num plugin custom único, o **SkyCore**.

| Função | Escolha | Notas |
| --- | --- | --- |
| Servidor | Paper 26.2 (alternativa: Leaf ou Purpur) | Fixar a versão, não usar "latest". Paper 26.2 pede Java 25. |
| Core de ilhas | SuperiorSkyblock2 (declara suporte a 26.1 e 26.2); alternativa: IridiumSkyblock | Upgrades com requisitos, island value, limites de blocos e entidades, ilhas no Nether e End. |
| Stack de mobs e spawners | WildStacker (declara 26.2) | Stacks virtuais, sem entidades a mais. |
| Dollars | Vault (VaultUnlocked) + economia simples | Sem Roses aqui, para não as expor a plugins de terceiros. |
| Roses | SkyCore (custom) | Base de dados própria, sem comando de troca. |
| Admin shop | EconomyShopGUI | Só para preços base de venda. |
| /ah e lojas de jogadores | A validar (QuickShop-Hikari, plugin de /ah existente, ou custom) | Tem de respeitar limites por ilha e taxa em Dollars. |
| Permissões | LuckPerms |  |
| Skills, magnet, minions, sell chests, wands, chunk hoppers, special crops, pets, missões, Path de Tiers, ranking | SkyCore (custom) | Não há plugin pronto que cubra o conjunto. |
| Observabilidade | spark (profiler), CoreProtect (logs/rollback) |  |

Suporte a versões é o declarado pelos autores em set. 2026 e não foi testado; confirmar na instalação.

**Risco a validar logo na Fase 0:** a maioria dos cores de ilhas dá uma ilha por jogador. As 3 ilhas simultâneas podem exigir camada custom ou outro core. O BentoBox suporta várias ilhas por jogador, mas o BSkyBlock não declara 26.2 ([fonte](https://www.gameserverkings.com/knowledge-base/minecraft/start-a-skyblock-server/)).

**Regras de performance (obrigatórias no design):**

1. **Magnet sem entidades.** Crops, ores e drops de mobs vão direto para o inventário; se estiver cheio, vão para a sell chest ou ficam num buffer virtual. Nunca criar item entities para estes drops.
2. **Minions virtuais.** Sem tick por minion: guardam dados (tipo, nível, timestamp) e calculam a produção em lote a cada X segundos. Representação visual com 1 entidade estática, ou nenhuma.
3. **Spawners com stack virtual.** Gerar poucos mobs por ciclo e multiplicar os drops pelo tamanho do stack.
4. **Sell chests e chunk hoppers em batch.** Um scheduler assíncrono por ilha, a cada 5-10 s, nunca um evento por item.
5. **Special crops sem replantar** por substituição direta do bloco com cooldown, sem ticks de crescimento extra.
6. **Limites por ilha** em minions, hoppers, spawners e entidades, aplicados no momento da colocação.
7. **Mundo void** com distância entre ilhas grande e border por ilha. A distância não muda depois de haver ilhas, por isso decide-se antes de abrir.
8. **Ajustes do Paper:** view-distance e simulation-distance baixas, activation range de entidades reduzido, hoppers otimizados, `max-entity-collisions` baixo.
9. **Dados em SQLite ou MySQL**, escritas assíncronas e agrupadas. Nunca I/O no thread principal.
10. **Metas a validar em teste de carga:** TPS 20 estável, MSPT médio abaixo de 25 ms com 40 jogadores, sem picos acima de 50 ms nas ações de venda e colheita.

## Economia: Dollars e Roses

Há duas moedas com papéis opostos: **Dollars** são volume e automação, **Roses** são escassez e esforço ativo. Nada converte uma na outra.

|  | Dollars ($) | Roses |
| --- | --- | --- |
| Fontes | Venda de itens (sell chests, sell wand, admin shop), missões diárias e de ilha, vendas entre jogadores (/ah e lojas) | Apenas os contadores de skills (ações ativas do jogador, ver "Skills") |
| Usos | Upgrades de ilha, unidades de spawner, sell chests, lojas, recargas de wand, taxas | Upgrades de ilha e especiais, minions e os seus upgrades, pets, special seeds, chunk hoppers, sell wands, ilhas extra, desbloqueio de tipos de spawner |
| Transferível | Sim, entre jogadores | Não: sem /pay, sem /ah, sem lojas, sem trocas |

**Regra soulbound.** Qualquer item cuja compra custe Roses fica vinculado à conta: não pode ser vendido, trocado, largado nem posto em loja. Sem isto, as Roses voltavam a poder ser compradas com Dollars, via mercado.

**Sinks de Dollars** (para controlar a inflação):

- Upgrades de ilha e de geradores (principal sink de longo prazo).
- Compra de spawners, sell chests, lojas e recargas de wand.
- /ah: taxa de listagem de 2% (não reembolsável) + taxa de venda de 5%.
- Lojas de jogadores: taxa de 3% por transação.
- Admin shop: compra a 4× o preço de venda base.

**Alavancas anti-inflação:**

1. O multiplicador total de venda (upgrade da ilha + bónus de skill + pet + prestígio) tem teto de ×4,0.
2. Preços base de venda fixos e definidos numa única tabela de configuração (ver "Preços base e calibração").
3. Telemetria semanal de Dollars criados e destruídos. Se a criação ultrapassar a destruição de forma sustentada, ativar preço dinâmico global por item (desce até 30% com volume alto e recupera com o tempo).

## Skills e progressão

Há quatro skills (Mining, Farming, Fishing, Combat). Cada ação ativa dá XP, e a cada N ações o jogador recebe Roses. Todas vão do nível 1 ao 100.

| Skill | Ação que conta | N (ações por payout) | XP por ação |
| --- | --- | --- | --- |
| Mining | Partir um bloco de gerador ou ore (nunca blocos colocados por jogadores) | 50 | pedra 1, carvão 3, quartzo 4, ferro 5, redstone/lapis 6, ouro 8, diamante 15, esmeralda 20, ancient debris 25 |
| Farming | Colher à mão um crop maduro (normal ou special) | 100 | 4 (special crop: 6) |
| Fishing | Apanhar um peixe ou item de pesca | 15 | 15 (tesouro: 60) |
| Combat | Matar um mob (último dano do jogador) | 35 | 5 a 25, conforme a vida do mob |

**Roses por payout = 1 + nível da skill ÷ 20 (arredondado para baixo).** Ou seja 1 Rose nos níveis 1-19, 2 nos 20-39, 3 nos 40-59, 4 nos 60-79, 5 nos 80-99 e 6 no nível 100. Isto dá cerca de 27-30 Roses por hora ativa por skill no nível 1 e cerca de 6× isso no nível 100.

**Curva de XP:** XP total para o nível L = 50 × L^2,2. Pontos de referência: nível 10 ≈ 8 mil, 25 ≈ 59 mil, 50 ≈ 272 mil, 75 ≈ 667 mil, 100 ≈ 1,26 milhões de XP.

**Regras de contagem (anti-abuso):**

1. Blocos colocados por jogadores são marcados e nunca contam para XP nem Roses.
2. Só contam ações do próprio jogador. Minions, hoppers, farms, golems, lava e TNT não dão XP nem Roses.
3. Num spawner com stack, conta 1 kill por mob morto pelo jogador, nunca por tamanho de stack.
4. Soft cap diário: 120 payouts por skill e por dia a 100%; acima disso, a 25%.
5. Sem contagem se o jogador estiver parado (mesma posição e mesmo olhar) mais de 60 s. Padrões repetitivos com intervalos idênticos geram um alerta para staff.
6. As Roses são creditadas no momento em que se completa cada N, com aviso na action bar.

**Perks por nível (todas as skills):**

- **Bónus de venda:** +0,5% por nível no valor de venda dos itens dessa skill (máx. +50%).
- **Nível 10:** perk utilitário. Mining: Haste I na ilha. Farming: auto-replant dos crops normais. Fishing: espera 20% mais curta. Combat: magnet de drops de mobs.
- **Níveis 25 / 50 / 75 / 100:** 5% / 10% / 15% / 20% de chance de drop duplo; no 100, título exclusivo.

**Magnet:** ativo desde o início para ores e crops (os itens vão direto para o inventário). Para mobs, desbloqueia no Combat 10. Se o inventário estiver cheio, o item cai no chão (comportamento normal).

## Ilhas, dimensões e geradores

**Decisão:** skyblock clássico, não OneBlock. Os geradores por dimensão, os minions e os upgrades de ilha encaixam melhor numa ilha que cresce em espaço do que num único bloco que regenera.

**Ilha inicial:** 40×40 blocos, uma árvore, baú inicial e o Gerador do Overworld nível 1 já ativo. Mundo void com border por ilha.

**Ilhas extra (máximo 3 por jogador):**

| Ilha | Requisito | Roses | Dollars |
| --- | --- | --- | --- |
| 1.ª (inicial) | Nenhum | Grátis | Grátis |
| 2.ª | Nível 5 na ilha principal | 500 | 250 000 |
| 3.ª | Nível 8 na ilha principal | 2 500 | 5 000 000 |

Cada ilha é independente: tem o seu Nível, upgrades, minions, spawners, geradores e dimensões, e começa no Nível 1. Skills, Roses, pets, tiers e prestígio são da conta. Uma ilha sem ninguém online fica descarregada: a produção é calculada ao regressar (ver Automação).

**Dimensões.** Cada ilha tem direito a uma mini-ilha no Nether e outra no End, com tamanho de 40% da ilha principal (mínimo 24×24). Os limites de minions, spawners e afins contam o total da ilha em todas as dimensões.

- **Nether:** portal de obsidiana normal, ativo a partir do Nível 3 da ilha. A mini-ilha cria-se ao primeiro uso.
- **End:** a partir do Nível 6. O portal exige 12 End Portal Frames e 12 Eyes of Ender. Os frames só saem do Gerador do Nether (nível 4 ou superior), com probabilidade muito baixa. Os olhos fazem-se com blaze powder (blazes do Nether) e ender pearls compradas no admin shop. O Ender Dragon está desativado.
- Os frames são trocáveis no /ah (saem do gerador, não custam Roses) e criam um mercado vivo.
- **Pity timer:** se um jogador partir 10 000 blocos do Gerador do Nether (nível 4+) sem sair frame, o próximo bloco é garantido.

**Regra dos geradores:** o nível de um gerador não pode ser superior ao Nível da ilha, e cada upgrade exige o valor mínimo de ilha indicado. Percentagens abaixo somam 100 em cada linha (o frame do End é uma probabilidade à parte).

**Gerador do Overworld**

| Nível | Pedra | Carvão | Ferro | Ouro | Redstone/Lapis | Diamante | Esmeralda | Valor mín. | Roses | Dollars |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 90 | 8 | 2 | 0 | 0 | 0 | 0 | — | — | Grátis |
| 2 | 76 | 12 | 8 | 0 | 4 | 0 | 0 | 3 000 | 20 | 15 000 |
| 3 | 66 | 12 | 10 | 3 | 8 | 1 | 0 | 15 000 | 50 | 80 000 |
| 4 | 56 | 12 | 12 | 5 | 10 | 4 | 1 | 60 000 | 120 | 350 000 |
| 5 | 46 | 12 | 14 | 7 | 12 | 6 | 3 | 250 000 | 250 | 1 200 000 |
| 6 | 38 | 11 | 15 | 8 | 14 | 9 | 5 | 800 000 | 450 | 4 000 000 |
| 7 | 30 | 10 | 16 | 9 | 15 | 12 | 8 | 3 000 000 | 800 | 12 000 000 |
| 8 | 24 | 9 | 16 | 10 | 16 | 15 | 10 | 9 000 000 | 1 400 | 40 000 000 |

**Gerador do Nether**

| Nível | Netherrack | Quartzo | Ouro | Glowstone | Ancient debris | Frame do End | Nível de ilha | Valor mín. | Roses | Dollars |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 85 | 10 | 5 | 0 | 0 | 0 | 3 | 20 000 | 40 | 100 000 |
| 2 | 75 | 12 | 8 | 5 | 0 | 0 | 4 | 80 000 | 100 | 400 000 |
| 3 | 67 | 13 | 9 | 8 | 3 | 0 | 5 | 300 000 | 220 | 1 500 000 |
| 4 | 60 | 14 | 10 | 10 | 6 | 0,03% | 6 | 1 000 000 | 400 | 5 000 000 |
| 5 | 54 | 14 | 11 | 11 | 10 | 0,03% | 7 | 3 500 000 | 700 | 15 000 000 |
| 6 | 48 | 14 | 12 | 12 | 14 | 0,05% | 8 | 10 000 000 | 1 200 | 50 000 000 |

Com 0,03% são precisos cerca de 40 000 blocos para 12 frames (perto de 27 h de mineração ativa); com 0,05%, cerca de 24 000 blocos.

**Gerador do End**

| Nível | End stone | Obsidiana | Purpur | Diamante | Esmeralda | Nível de ilha | Valor mín. | Roses | Dollars |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 80 | 10 | 8 | 2 | 0 | 6 | 1 000 000 | 300 | 3 000 000 |
| 2 | 70 | 10 | 8 | 8 | 4 | 7 | 3 500 000 | 600 | 12 000 000 |
| 3 | 60 | 10 | 8 | 12 | 10 | 8 | 10 000 000 | 1 100 | 40 000 000 |
| 4 | 50 | 10 | 8 | 16 | 16 | 9 | 28 000 000 | 2 000 | 150 000 000 |

## Upgrades de ilha

Cada ilha tem um **Nível de Ilha (1 a 10)** que define o espaço e todos os limites de uma vez, mais alguns upgrades especiais independentes. Todo o upgrade exige três coisas ao mesmo tempo: **valor mínimo da ilha, Roses e Dollars**. O valor da ilha é a soma dos blocos e itens colocados (pesos em "Preços base e calibração"), contando as três dimensões. Os upgrades nunca regridem: se o valor descer depois, o que já foi comprado mantém-se. Só o dono da ilha compra upgrades e paga com o seu saldo.

**Limites por Nível de Ilha**

| Nível | Tamanho | Minions | Hoppers | Chunk hoppers | Sell chests | Lojas (buy/sell) | Stack máx. de spawner (Overworld) | Spawners totais |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 40×40 | 3 | 20 | 0 | 2 | 0 | — | 0 |
| 2 | 60×60 | 6 | 40 | 1 | 4 | 2 | 1 | 5 |
| 3 | 80×80 | 10 | 70 | 2 | 6 | 4 | 10 | 30 |
| 4 | 100×100 | 15 | 100 | 3 | 8 | 6 | 10 | 80 |
| 5 | 125×125 | 20 | 150 | 5 | 12 | 8 | 100 | 250 |
| 6 | 150×150 | 26 | 200 | 7 | 16 | 12 | 100 | 600 |
| 7 | 180×180 | 33 | 260 | 10 | 20 | 16 | 500 | 1 500 |
| 8 | 210×210 | 40 | 330 | 14 | 26 | 20 | 1 000 | 4 000 |
| 9 | 250×250 | 50 | 400 | 18 | 32 | 26 | 2 500 | 8 000 |
| 10 | 300×300 | 60 | 500 | 24 | 40 | 32 | 5 000 | 15 000 |

O stack máximo aplica-se por bloco de spawner no Overworld. No Nether é 50% desse valor e no End 20%.

**Custos e requisitos por Nível de Ilha**

| Para o nível | Valor mín. da ilha | Roses | Dollars | Desbloqueia também |
| --- | --- | --- | --- | --- |
| 2 | 5 000 | 40 | 10 000 | Chunk hoppers, spawners, lojas |
| 3 | 20 000 | 100 | 50 000 | Portal do Nether |
| 4 | 80 000 | 200 | 250 000 |  |
| 5 | 300 000 | 400 | 1 000 000 | 2.ª ilha |
| 6 | 1 000 000 | 750 | 4 000 000 | Portal do End |
| 7 | 3 500 000 | 1 200 | 15 000 000 |  |
| 8 | 10 000 000 | 2 000 | 50 000 000 | 3.ª ilha |
| 9 | 28 000 000 | 3 200 | 200 000 000 |  |
| 10 | 75 000 000 | 5 000 | 700 000 000 | Elegibilidade para Prestígio |

**Multiplicador de venda das sell chests (upgrade geral da ilha)**

| Nível | Multiplicador | Nível de ilha mín. | Valor mín. | Roses | Dollars |
| --- | --- | --- | --- | --- | --- |
| 1 | ×1,00 | 1 | — | — | — |
| 2 | ×1,10 | 2 | 5 000 | 30 | 20 000 |
| 3 | ×1,25 | 3 | 20 000 | 80 | 100 000 |
| 4 | ×1,40 | 4 | 80 000 | 160 | 400 000 |
| 5 | ×1,60 | 5 | 300 000 | 320 | 1 500 000 |
| 6 | ×1,85 | 6 | 1 000 000 | 600 | 6 000 000 |
| 7 | ×2,15 | 7 | 3 500 000 | 1 000 | 20 000 000 |
| 8 | ×2,50 | 8 | 10 000 000 | 1 800 | 60 000 000 |

**Crescimento das crops (faster crops)**

O upgrade é percentual e reduz o tempo de crescimento das special crops e das crops normais. Sem upgrade, uma special crop rebrota em 10 min. As crops normais (trigo, cenoura, batata, beterraba, nether wart, cacau, caules de abóbora/melancia, sweet berries, sugar cane e cacto) crescem nas ilhas ×2 mais depressa do que no vanilla.

| Nível | Redução do tempo | Special crop | Crops normais | Nível de ilha mín. | Valor mín. | Roses | Dollars |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Base | 0% | 10 min | ×2 | 1 | — | — | — |
| 1 | −10% | 9 min | ×2,2 | 2 | 5 000 | 25 | 15 000 |
| 2 | −20% | 8 min | ×2,5 | 3 | 20 000 | 60 | 80 000 |
| 3 | −30% | 7 min | ×2,9 | 4 | 80 000 | 120 | 300 000 |
| 4 | −40% | 6 min | ×3,3 | 6 | 1 000 000 | 250 | 1 500 000 |
| 5 | −50% | 5 min | ×4 | 8 | 10 000 000 | 500 | 6 000 000 |

Os geradores por dimensão têm as suas próprias tabelas de upgrade (ver Ilhas, dimensões e geradores). Os minions são melhorados um a um (ver Automação). A sell chest não tem upgrade individual.

## Automação: special crops, minions, hoppers e sell chests

A automação gera Dollars e itens, nunca XP nem Roses. É calculada em lote e não precisa de chunks carregados: ao descarregar uma ilha guarda-se o timestamp, e ao carregá-la calcula-se a produção de minions e spawners até um máximo de **12 h acumuladas**, respeitando o armazenamento, e vende-se pelas sell chests. Hoppers e chunk hoppers físicos só funcionam com a ilha carregada.

### Special crops

Vendidas em **packs de 16 sementes especiais** (soulbound, pagas em Roses + Dollars). Cada semente colocada é um bloco permanente: ao colher à mão, o drop vai direto para o inventário (magnet) e o crop volta ao estágio 0, rebrotando sozinho ao fim do tempo de rebrote da ilha (base 10 min). Nunca é preciso replantar. O rebrote usa um scheduler próprio, sem ticks aleatórios.

| Special crop | Farming mín. | Roses por pack | Dollars por pack | Drop por colheita |
| --- | --- | --- | --- | --- |
| Trigo | 1 | 8 | 2 000 | 3 trigo |
| Cenoura | 5 | 8 | 2 000 | 3 cenouras |
| Batata | 5 | 8 | 2 000 | 3 batatas |
| Beterraba | 10 | 10 | 3 000 | 3 beterrabas |
| Abóbora | 15 | 14 | 5 000 | 2 abóboras |
| Melancia | 15 | 14 | 5 000 | 6 fatias |
| Cana-de-açúcar | 20 | 16 | 6 000 | 3 canas |
| Nether wart (só na ilha do Nether) | 25 | 24 | 10 000 | 4 nether warts |
| Cacau | 30 | 24 | 10 000 | 3 cacau |

### Minions

O minion é um item soulbound colocado na ilha. É **virtual**: não parte nem coloca blocos, só produz 1 item por ciclo e envia-o para a sell chest ligada (a até 5 blocos) ou para o seu armazém interno. Cada minion é melhorado **individualmente**. Quando o armazém enche, pára.

- **Mining:** cobblestone, carvão, ferro, ouro, redstone, lapis, diamante, esmeralda no Overworld; quartzo, glowstone e ancient debris no Nether. Só produz o que o gerador da ilha já desbloqueou.
- **Farming:** trigo, cenoura, batata, abóbora, melancia, cana-de-açúcar (e nether wart na ilha do Nether). Exige o Farming do crop correspondente.
- **Fishing:** peixes (Fishing 10).
- **Combat:** vaca, zombie, esqueleto, aranha (Combat 5). Os spawners cobrem o resto.
- Itens raros (frames do End) nunca saem de minions.

| Tier | Ciclo | Armazém (stacks) | Roses | Dollars | Skill mín. do tipo |
| --- | --- | --- | --- | --- | --- |
| I (compra) | 30 s | 3 | 10 | 5 000 | 1 |
| II | 27 s | 6 | 5 | 10 000 | 5 |
| III | 24 s | 9 | 10 | 30 000 | 10 |
| IV | 21 s | 12 | 25 | 80 000 | 15 |
| V | 18 s | 15 | 50 | 250 000 | 25 |
| VI | 15 s | 18 | 100 | 700 000 | 40 |
| VII | 12 s | 21 | 200 | 2 000 000 | 55 |
| VIII | 9 s | 24 | 400 | 6 000 000 | 70 |

Cada linha é o custo de subir para esse tier. Para um minion completo (I a VIII), o total é 800 Roses e cerca de 9,1 milhões de Dollars. Minions de diamante e esmeralda pagam ×3 nos Dollars.

### Hoppers e chunk hoppers

- **Hopper:** vanilla com as otimizações do Paper, limitado pelo Nível de Ilha.
- **Chunk hopper:** recolhe os itens soltos do seu chunk para um inventário interno de 54 slots (ciclo de 2 s) e pode alimentar uma sell chest adjacente. No máximo 1 por chunk. Custa **40 Roses + 25 000 Dollars** e é soulbound. Serve sobretudo para farms de mobs e de crops automáticas.

### Sell chests

Compram-se por **5 000 Dollars** (sem Roses, trocam-se). A cada 5 s vendem tudo o que contêm por: preço base × multiplicador da ilha × bónus da skill da categoria × bónus de pet (teto total ×4,0). Itens sem preço ficam lá dentro. O dinheiro vai para o dono da ilha, mesmo offline, e as notificações de venda agrupam-se num resumo por minuto. O upgrade de multiplicador é da ilha toda (ver Upgrades de ilha).

## Spawners e stacks

Os spawners são a grande fonte de Dollars passivos. Cada spawner é um bloco que guarda N unidades (stack) e só pode ser colocado na **dimensão nativa do mob**: Overworld, Nether ou End. A regra é validada ao colocar e ao mover o item.

**Compra em duas partes:**

- **Desbloquear o tipo** (uma vez por conta, em Roses, exige o nível de Combat indicado). Fica vinculado à conta.
- **Comprar unidades** (só Dollars, ao preço fixo da tabela). As unidades trocam-se no /ah, mas só se colocam se o tipo estiver desbloqueado. Assim as Roses limitam o acesso, e os Dollars limitam o volume.

**Stack:** clicar num spawner do mesmo tipo com uma unidade (ou um item de spawner com várias) junta-as até ao máximo do Nível de Ilha (ver Upgrades de ilha: 1, 10, 100, 500, 1 000, 2 500 ou 5 000 no Overworld; 50% no Nether; 20% no End). O total de unidades por ilha também tem teto por Nível. Com shift+clique de mão vazia, o stack volta a item com o número guardado.

**Como funciona (um bloco, uma entidade):**

1. A cada ciclo de 20 s, o spawner cria **uma única entidade** que representa N÷3 mobs (acumulando frações). Isto equivale a 60 mobs por hora por unidade.
2. Se o jogador a matar, recebe o loot de todos os mobs do stack (magnet, se desbloqueado) e **1 ação** de Combat, com o XP de um único mob.
3. Se ninguém a matar em 20 s, o sistema "colhe-a" virtualmente. O loot vai para o armazém interno ou para a sell chest ligada, sem XP nem Roses.
4. Assim o rendimento de Dollars é igual com ou sem jogador, e só o jogador ativo ganha XP e Roses. No máximo existe 1 entidade viva por bloco de spawner.

| Mob | Dimensão | Combat mín. | Roses (desbloqueio) | Dollars por unidade | Loot base por mob | Dollars/h por unidade |
| --- | --- | --- | --- | --- | --- | --- |
| Vaca | Overworld | 1 | 5 | 9 000 | $3 | 180 |
| Zombie | Overworld | 5 | 10 | 15 000 | $5 | 300 |
| Esqueleto | Overworld | 10 | 15 | 25 000 | $7 | 420 |
| Aranha | Overworld | 15 | 25 | 40 000 | $9 | 540 |
| Slime | Overworld | 20 | 40 | 60 000 | $12 | 720 |
| Creeper | Overworld | 25 | 60 | 75 000 | $14 | 840 |
| Golem de ferro | Overworld | 35 | 100 | 140 000 | $22 (1 lingote de ferro) | 1 320 |
| Zombified piglin | Nether | 25 | 80 | 80 000 | $9 | 540 |
| Magma cube | Nether | 30 | 120 | 140 000 | $16 | 960 |
| Blaze | Nether | 35 | 200 | 270 000 | $30 (blaze rods) | 1 800 |
| Wither skeleton | Nether | 45 | 350 | 400 000 | $42 (sem skulls) | 2 520 |
| Enderman | End | 50 | 400 | 500 000 | $45 (ender pearls) | 2 700 |
| Shulker | End | 60 | 600 | 450 000 | $37,50 (25% de 1 esmeralda) | 2 250 |

**Shulker:** ao morrer só dá esmeraldas (25% de chance de 1 por mob, sem shulker shells). O payback de uma unidade ronda 50 h no Overworld inicial, 150 h no Nether e 200 h no End, ainda antes do multiplicador de venda.

**Regras adicionais:**

- Só o dono e os membros da ilha partem ou mexem num spawner.
- Sem orbs de XP vanilla e sem drops raros de armas ou armaduras.
- Cada unidade conta no valor da ilha a 25% do seu preço.

## Pets

Os pets compram-se **apenas com Roses**, são soulbound e dão bónus de eficiência. Nunca aumentam as Roses ganhas, para que as Roses continuem a depender só do esforço ativo. Cada jogador tem 1 pet ativo (2 a partir do Tier 10).

**Pets disponíveis:**

- **Toupeira** (Mining), **Coelho** (Farming), **Lontra** (Fishing), **Lobo** (Combat): bónus de XP da sua skill e de valor de venda dos itens dessa skill.
- **Raposa:** bónus de valor de venda geral.
- **Abelha:** reduz o tempo de rebrote dos special crops.

**Raridades e preços** (bónus máximo ao nível 50; o bónus cresce linearmente com o nível):

| Raridade | Roses | XP da skill (pets de skill) | Venda da skill (pets de skill) | Venda geral (Raposa) | Rebrote (Abelha) |
| --- | --- | --- | --- | --- | --- |
| Comum | 150 | +10% | +2% | +1,5% | −3% |
| Raro | 600 | +20% | +4% | +3% | −6% |
| Épico | 2 000 | +35% | +7% | +5% | −10% |
| Lendário | 6 000 | +50% | +10% | +8% | −15% |

**Evolução:** pode subir-se um pet para a raridade seguinte pagando a diferença em Roses (Raro 450, Épico 1 400, Lendário 4 000), mantendo o nível e o XP.

**Níveis:** 1 a 50. XP total do pet = 25 × L^2,2 (nível 50 ≈ 136 mil). O pet ganha 50% do XP que a skill ganha com ações ativas, e nada com automação.

**Onde se obtêm:** compra com Roses, ou como recompensa de missões de ilha e de tiers (nesse caso são ovos Comum ou Raro, também soulbound).

**Performance:** sem entidade por omissão. Visual opcional como item display, visível só para quem estiver a menos de 24 blocos.

## Mercado: /ah, lojas e sell wands

O mercado entre jogadores usa só Dollars e serve também de sink através das taxas. Itens soulbound nunca entram nele.

### /ah (action house)

- Preço mínimo de listagem: 50% do preço base de venda do item (itens sem preço base: 1 Dollar). Sem preço máximo.
- Duração: 48 h. Se expirar, o item volta ao dono numa caixa de recolha.
- Taxa de listagem de 2% (não reembolsável) e taxa de venda de 5%, descontada ao vendedor.
- Número máximo de listagens: 5 + o Nível da ilha mais alta do jogador.
- Pode vender-se: recursos, drops, unidades de spawner, frames do End e outros itens não soulbound. Não se pode vender: pets, minions, chunk hoppers, special seeds, sell wands e tudo o que tenha custado Roses.
- Pesquisa por nome e por categoria, ordenada por preço unitário.
- Alerta automático para staff em vendas repetidas entre as mesmas duas contas a menos de 10% do preço base (suspeita de transferência disfarçada).

### Lojas de jogadores (buy/sell chests)

- A chest de loja compra-se por **10 000 Dollars** (trocável) e o limite por ilha depende do Nível (ver Upgrades de ilha).
- O dono define o item e os preços num menu. Modos: **venda** (a loja vende ao jogador), **compra** (a loja compra ao jogador, a partir do saldo do dono) ou ambos.
- Preço de compra e de venda limitado entre 10% e 500% do preço base do item.
- Taxa de 3% por transação, paga por quem vende. Não aceita itens soulbound.
- Cada ilha tem um warp de lojas. O comando `/shops <item>` lista as lojas com o preço mais baixo e teleporta para a ilha respetiva. As compras fazem-se presencialmente.

### Sell wands

A wand vende o conteúdo de um contentor com um clique direito. Compra-se com Roses (soulbound) e também sai como recompensa de missões e tiers.

| Tier | Usos | Cooldown | Alcance | Preço | Venda face à sell chest |
| --- | --- | --- | --- | --- | --- |
| I | 100 | 3 s | 1 contentor | 120 Roses + 50 000 Dollars | 90% |
| II | 500 | 1,5 s | 1 contentor | 400 Roses + 250 000 Dollars | 95% |
| III | 2 000 | 0,5 s | Todos os contentores a até 5 blocos | 1 200 Roses + 1 500 000 Dollars | 100% |

A recarga custa 200 Dollars por uso, em qualquer tier (sink de Dollars).

**Comando /sell:** `/sell hand` e `/sell all` vendem ao preço base, sem multiplicador de ilha nem bónus. O `/sell all` tem cooldown de 30 s. O multiplicador só se aplica através das sell chests e das wands.

## Missões e objetivos

O jogador nunca fica sem objetivo porque há quatro horizontes em simultâneo: **diário** (missões diárias), **curto prazo** (missões de ilha), **médio prazo** (Path de Tiers) e **longo prazo** (Prestígio). Nenhuma recompensa dá Roses diretamente; dão Dollars, itens e títulos. As Roses continuam a vir só das skills.

**Visibilidade:** o scoreboard mostra sempre o próximo requisito em falta do Tier atual, com barra de progresso. O comando `/path` abre o checklist completo e `/missoes` as missões diárias e de ilha.

### Missões diárias

- 3 por dia (fácil, média, difícil), geradas às 00:00 (Europa/Lisboa) a partir de modelos ligados às skills do jogador. Exemplo: "minera 500 blocos", "pesca 40 peixes", "mata 150 mobs", "colhe 600 crops".
- Meta = valor base × (1 + nível da skill ÷ 50). Contam só ações ativas.
- Recompensa: Dollars conforme o Nível da ilha mais alta do jogador, mais 50 usos de sell wand (as missões diárias nunca dão special seeds). Streak de 7 dias seguidos dá 1 chunk hopper.

| Nível de ilha | Fácil | Média | Difícil |
| --- | --- | --- | --- |
| 1-2 | 1 000 | 2 500 | 5 000 |
| 3-4 | 8 000 | 20 000 | 40 000 |
| 5-6 | 80 000 | 200 000 | 400 000 |
| 7-8 | 500 000 | 1 200 000 | 2 500 000 |
| 9-10 | 3 000 000 | 7 500 000 | 15 000 000 |

### Missões de ilha

Corrente de 20 missões (2 por Nível), por ilha. Só ficam ativas quando a ilha atinge o Nível indicado. As recompensas em itens (pets, wands, chunk hoppers, seeds) só se recebem **uma vez por conta**; nas ilhas extra recebe-se apenas a parte em Dollars.

| Nível | Missão | Objetivo | Recompensa |
| --- | --- | --- | --- |
| 1 | Primeira colheita | Colher 100 crops à mão | 2 000 Dollars + 1 pack de trigo |
| 1 | Pedra sobre pedra | Partir 500 blocos do gerador | 3 000 Dollars |
| 2 | Vendas automáticas | Vender 20 000 Dollars em sell chests | 10 000 Dollars + 1 chunk hopper |
| 2 | Minion inaugural | Colocar 1 minion | 8 000 Dollars + 1 pack de cenoura |
| 3 | Para o inferno | Ativar o portal e visitar o Nether | 25 000 Dollars + ovo de pet Comum |
| 3 | Brilho do Nether | Partir 200 quartzos do gerador | 40 000 Dollars |
| 4 | Primeiro spawner | Colocar um stack de 10 zombies | 100 000 Dollars + 2 chunk hoppers |
| 4 | Loja aberta | Colocar uma chest de loja e fazer 5 vendas | 120 000 Dollars + sell wand I |
| 5 | Segunda casa | Comprar a 2.ª ilha | 400 000 Dollars + ovo de pet Raro |
| 5 | Veio de diamante | Partir 300 diamantes | 600 000 Dollars + sell wand II |
| 6 | Portal final | Ativar o portal do End | 2 000 000 Dollars + 3 chunk hoppers |
| 6 | Frota de minions | Ter 10 minions de tier V ou superior | 3 000 000 Dollars + 1 pack de cacau |
| 7 | Exército | Stack de 100 spawners num único bloco | 8 000 000 Dollars + ovo de pet Épico |
| 7 | Sinfonia de vendas | Vender 50 milhões de Dollars em sell chests | 10 000 000 Dollars |
| 8 | Terceira ilha | Comprar a 3.ª ilha | 30 000 000 Dollars + sell wand III |
| 8 | Mestre minerador | Mining nível 60 | 25 000 000 Dollars + 5 chunk hoppers |
| 9 | Barão do End | Stack de 500 shulkers | 100 000 000 Dollars + ovo de pet Lendário |
| 9 | Arqueologia | Partir 24 000 blocos do gerador do End | 120 000 000 Dollars |
| 10 | Ilha perfeita | Todos os upgrades da ilha no máximo | 350 000 000 Dollars + título e cosmético |
| 10 | Chegada ao topo | As 4 skills em nível 75 ou superior | 400 000 000 Dollars + 5 chunk hoppers |

### Path de Tiers (carreira da conta)

São 10 tiers com checklist. Os tiers não bloqueiam mecânicas (isso é feito pelos Níveis e pelas skills): servem de objetivo visível e de fonte de recompensas. O tempo-alvo é para jogo ativo de cerca de 1,5 h por dia e serve de meta de calibração.

| Tier | Nome | Requisitos | Recompensas | Tempo-alvo (dias) |
| --- | --- | --- | --- | --- |
| 1 | Náufrago | Início | Guia inicial | 0 |
| 2 | Colono | Ilha Nível 2; Farming 5; Mining 5 | 1 chunk hopper, 1 pack de trigo, título | 2 |
| 3 | Explorador | Nível 3; visitar o Nether; Mining 15; Combat 10 | Sell wand I, 1 minion tier I à escolha | 6 |
| 4 | Industrial | Nível 4; 5 minions; Farming 20; Fishing 15 | Ovo de pet Comum, 2 chunk hoppers | 14 |
| 5 | Magnata | Nível 5; 2.ª ilha; 5 milhões de Dollars vendidos no total; Mining 30; Combat 30 | Sell wand II, ovo de pet Raro | 30 |
| 6 | Desbravador | Nível 6; portal do End ativo; as 4 skills em 35 | 1 minion tier IV à escolha, título | 50 |
| 7 | Barão | Nível 7; stack de 100 num spawner; 20 minions de tier IV ou superior; skills em 50 | Ovo de pet Épico | 80 |
| 8 | Imperador | Nível 8; 3.ª ilha; skills em 60 | Sell wand III, título | 115 |
| 9 | Lenda | Nível 9; geradores no máximo (Overworld 8, Nether 6, End 4); skills em 75 | Ovo de pet Lendário | 160 |
| 10 | Ascendente | Nível 10; skills em 90 | 2.º slot de pet, título, acesso ao Prestígio | 220 |

### Prestígio (Ascensão)

Disponível no Tier 10. O jogador **reinicia** todas as ilhas, Dollars, minions, spawners, upgrades e geradores, e **mantém** skills, Roses, pets, tipos de spawner desbloqueados e títulos. Ganha 1 Estrela (máximo 10): +3% no valor de venda (dentro do teto ×4,0), +5% de XP em todas as skills, tag e border de ilha exclusivas, e a ilha nova arranca já no Nível 1 + n.º de Estrelas (máx. Nível 5), sem pagar os custos desses níveis.

## Ranking semanal

Há quatro rankings por semana, para que jogadores ativos, construtores e "magnatas" tenham todos algo a disputar. A semana fecha na segunda-feira às 00:00 (Europa/Lisboa), com um snapshot e a entrega de recompensas por `/rewards`.

| Ranking | Mede | Para quem é |
| --- | --- | --- |
| Esforço | Roses ganhas na semana | Jogadores muito ativos |
| Maestria | XP total ganho nas 4 skills | Quem sobe de nível |
| Construção | Aumento do valor total das ilhas do jogador (final menos início da semana) | Quem investe e constrói |
| Comércio | Dollars ganhos em sell chests e wands | Quem tem automação forte |

**Recompensas por ranking** (nunca Roses):

| Posição | Recompensa |
| --- | --- |
| 1.º | Título "Campeão da Semana" (30 dias), ovo de pet Raro, 3 chunk hoppers |
| 2.º | Ovo de pet Comum, 2 chunk hoppers |
| 3.º | Ovo de pet Comum, 1 chunk hopper |
| 4.º a 10.º | 5 packs de special seeds e 200 usos de sell wand |
| 11.º a 50.º | 1 pack de special seeds |

**Anti-abuso:**

1. Os rankings de Esforço e de Maestria usam os valores já sujeitos ao soft cap diário e às regras anti-AFK.
2. Para ser elegível, é preciso pelo menos 1 h de jogo ativo na semana.
3. O valor da ilha no ranking de Construção é sempre o líquido da semana, para que colocar e retirar blocos não conte.
4. Contas banidas ou sob investigação ficam excluídas. O staff não participa.
5. Em empate ganha quem chegou primeiro ao valor.

O ranking é individual: o valor das ilhas conta para o dono. Mostram-se em `/top` e num holograma no spawn. Um webhook opcional pode anunciar os vencedores num canal de chat externo.

## Preços base e calibração

Todos os números deste documento são **valores iniciais coerentes entre si**, não verdades finais. Devem viver em ficheiros de configuração (YAML) versionados, para ajustar sem mexer em código, e ser validados com um simulador antes de abrir o servidor.

### Preços base de venda (admin shop)

A compra no admin shop custa 4× o preço de venda. Itens não listados não se vendem nas sell chests.

| Grupo | Item e preço de venda (Dollars) |
| --- | --- |
| Pedra e geradores | Cobblestone 2, netherrack 1, end stone 1, obsidiana 12, purpur 6 |
| Minerais | Carvão 8, ferro 20, ouro 35, redstone 12, lapis 15, diamante 120, esmeralda 150 |
| Nether | Quartzo 10, glowstone 8, ancient debris 150, blaze rod 60, nether wart 10 |
| Farming | Trigo 4, cenoura 4, batata 4, beterraba 5, abóbora 6, fatia de melancia 2, cana 5, cacau 6 |
| Pesca e mobs | Peixe 15, ender pearl 90 (mais os loots indicados em Spawners) |

### Valor da ilha (island value)

Soma de tudo o que está na ilha e nas suas dimensões. Blocos valem o seu preço de venda unitário (blocos de armazenamento: 9× o item). Blocos sem preço valem 0.

| Elemento | Valor contado |
| --- | --- |
| Spawner (unidade) | 25% do preço de compra |
| Minion | 50% do custo acumulado em Dollars |
| Sell chest / chest de loja | 5 000 / 10 000 |
| Chunk hopper | 25 000 |
| Hopper | 100 |
| Special crop plantada | 500 |

### Tempo-alvo por Nível de Ilha (jogo ativo de cerca de 1,5 h por dia)

| Nível | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Dias acumulados | 1 | 4 | 10 | 24 | 45 | 75 | 110 | 160 | 220 |

### Âncoras de balanceamento

- **Dollars por hora de jogo ativo** no Nível 1: cerca de 4 000 (1 500 blocos × cerca de 2,8 Dollars por bloco).
- **Roses por hora ativa** no nível 1 de uma skill: 27 a 30.
- **Oferta de Roses** (estimativa): cerca de 55 por dia no início e cerca de 330 por dia com as 4 skills a 100. Ao longo de 220 dias dá à volta de 28 mil.
- **Sinks de Roses** só para maximizar ilhas, geradores, multiplicador de venda, crescimento e ilhas extra: cerca de 30,6 mil. Somam-se pets (até 6 000 cada), minions (800 por minion completo), spawners (cerca de 2 mil para desbloquear tudo), chunk hoppers e wands. A escassez é intencional: o jogador tem de escolher em que investir.
- **Preço das unidades de spawner** tem payback de 50 a 200 h por unidade, antes de multiplicadores.

### Simulação e telemetria

1. Antes de abrir, construir um simulador (script ou folha de cálculo) que, para 3 perfis (casual, regular, hardcore), projete Dollars, Roses, XP e o tempo para cada Nível e Tier. Ajustar os números até coincidirem com os tempos-alvo.
2. Registar semanalmente: Dollars criados e destruídos por fonte, Roses criadas e gastas, tempo médio por Nível, percentagem de jogadores por Tier, ponto onde os jogadores deixam de jogar, e TPS/MSPT.
3. Se o tempo real para um Nível ficar 30% fora do alvo, ajustar o custo em Dollars primeiro, depois as Roses.

## Plano de implementação

A construção faz-se em 7 fases sequenciais. Cada fase só fecha quando o seu critério de aceitação passa em teste. Toda a lógica específica vive no plugin SkyCore, com os números em config YAML.

| Fase | Entregáveis | Critério de aceitação |
| --- | --- | --- |
| 0. Validação | Paper (ou Leaf) instalado; core de ilhas escolhido (SuperiorSkyblock2 ou IridiumSkyblock); esqueleto do SkyCore (loader, config, base de dados); spike das 3 ilhas por jogador e das mini-ilhas Nether/End | 3 ilhas por jogador funcionam ou existe fallback decidido; distância entre ilhas definida; TPS 20 com bots |
| 1. Núcleo económico | Dollars (Vault), Roses (BD, sem transferência), sistema soulbound, 4 skills com XP, contadores e soft cap, anti-AFK, magnet, admin shop, /sell | Minion, farm ou AFK nunca dão Roses nem XP; cap diário funciona; Roses não se transferem por nenhum caminho |
| 2. Ilha e progressão | Nível de Ilha, valor da ilha, upgrades com requisitos, geradores das 3 dimensões, portais, frames e pity timer, ilhas extra, multiplicador de venda, crescimento | Todas as tabelas aplicadas por config; upgrades bloqueados sem valor, Roses e Dollars suficientes |
| 3. Automação | Special crops, minions virtuais, hoppers e chunk hoppers, sell chests, produção offline, spawners com stacks e restrição por dimensão | Teste de carga com 40 jogadores simulados e ilhas de Nível 6: MSPT médio abaixo de 25 ms; magnet sem item entities |
| 4. Mercado e pets | /ah, chests de loja, /shops, sell wands, pets | Itens soulbound não são listados nem trocados; taxas aplicadas; testes de duplicação passam |
| 5. Objetivos | Missões diárias e de ilha, Path de Tiers, Prestígio, scoreboard e menus, ranking semanal | Um jogador novo vê o próximo objetivo em 1 clique; o ciclo semanal fecha e paga sem erros |
| 6. Calibração e abertura | Simulador, beta fechado, telemetria, backups, regras e staff | Tempos reais por Nível a menos de 30% do alvo; backups restaurados com sucesso num teste |

**Testes de segurança e exploração a cobrir** (cada um com um caso de teste escrito):

1. Duplicação de itens (spawners, minions, items soulbound, pets).
2. Conversão indireta de Dollars em Roses (loja, /ah, trocas, chests, drops).
3. Macros e AFK para farmar Roses.
4. Inflar valor de ilha com blocos colocados e retirados.
5. Portais e dimensões: criar mini-ilhas extra ou fugir aos limites.
6. Spawners colocados na dimensão errada, ou stacks acima do limite.

**Operação:** backups diários da base de dados e dos mundos com retenção de 14 dias, CoreProtect para rollback, restart diário fora de horas de ponta, LuckPerms com papéis claros (jogador, helper, admin).

**Decisões (perguntas em aberto respondidas a 16 nov. 2026):**

- Onde corre: Mac mini, num container Docker (ver `README.md`). Poucos jogadores simultâneos (`max-players=20`).
- Aberto ao público (sem whitelist), com `online-mode=true`.
- As ilhas têm membros (máx. 4 + dono, configurável). Roses e Dollars **não** pertencem à ilha: cada membro usa o seu saldo e só o dono compra upgrades.
- Versão fixada: Minecraft / Paper **26.3** (build 253), Java 25.
- Idioma do servidor: inglês.

**Desvios à stack técnica decididos na implementação:**

- Ilhas, spawners com stack, admin shop, /ah e lojas foram implementados no SkyCore. O SuperiorSkyblock2 não tem versão para 26.3 e dá 1 ilha por jogador. O WildStacker e o EconomyShopGUI deixaram de ser necessários.
- O CoreProtect CE 24.1 recusa arrancar em 26.3. Fica de fora até haver versão compatível; até lá, o rollback faz-se com os backups diários.
