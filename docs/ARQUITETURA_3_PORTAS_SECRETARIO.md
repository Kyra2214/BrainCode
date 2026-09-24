# BrainCode — Arquitetura das 3 Portas e Agente Secretário

**Status:** Decisão arquitetural / visão futura  
**Data:** 20/09/2026

## 1. Conceito

O BrainCode terá um **Agente Secretário** como ponto inicial de entrada. Ele recebe a ordem do usuário, identifica a intenção e encaminha para uma de três portas:

1. **Porta 1 — Chat / Plano**
2. **Porta 2 — Prompt**
3. **Porta 3 — Criação / Desenvolvimento**

Cada porta possui agentes especialistas, regras, permissões e ciclo de vida próprios.

```
                         USUÁRIO
                            │
                            ▼
                     ┌─────────────┐
                     │  SECRETÁRIO │
                     └──────┬──────┘
                            │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
         🚪 PORTA 1    🚪 PORTA 2    🚪 PORTA 3
            CHAT          PROMPT        CRIAÇÃO
              │             │             │
        especialistas  especialistas  especialistas
```

## 2. Regra global de Web e APIs

**Web é liberada para as três portas.**

| Porta | Web | API externa agora |
|---|---|---|
| Chat / Plano | ✅ | ❌ |
| Prompt | ✅ | ❌ |
| Criação | ✅ | ❌ |

A Web pode ser usada quando o fluxo precisar e conforme suas regras.

**APIs externas ficam fora da fase atual.** A integração será feita posteriormente, quando o restante do BrainCode estiver consolidado. No futuro, especialmente na Porta 3, APIs/providers poderão ser liberados conforme especialidade, necessidade e Policy.

## 3. Agente Secretário

O Secretário é o porteiro/orquestrador inicial.

Responsabilidades:
- interpretar a ordem;
- identificar intenção e contexto;
- detectar mudança de modo;
- identificar restrições;
- determinar a porta;
- encaminhar para os especialistas;
- preservar permissões e restrições.

Ele **não executa** o trabalho especializado e não substitui Planner, Router, Policy, Critic ou Readiness.

Os agentes permanecem especializados e amarrados aos contratos e regras do BrainCode. Não é objetivo colocar uma LLM independente dentro de cada agente.

## 4. Porta 1 — Chat / Plano

É o espaço de conversa normal, pesquisa e planejamento.

Exemplos:
- “Que dia é hoje?”
- “Quais lugares legais existem para viajar?”
- “Estou pensando em criar um aplicativo.”
- “Vamos discutir a arquitetura.”

Papéis especialistas previstos:

```
Porta 1 — CHAT
├── Conversation Agent
├── Research Agent
├── Analysis Agent
├── Planning Agent
└── Context / Memory
```

Permissões conceituais:
- conversa ✅
- planejamento ✅
- Web ✅
- pesquisa ✅
- criação de projeto ❌
- execução ❌
- APIs externas ❌ agora

A conversa pode evoluir e acumular contexto sem iniciar automaticamente o desenvolvimento.

No caminho Android, a Porta 1 é materializada pelo passo `chat.respond`, executado localmente pelo `ChatResponseExecutor`. O `DoorAwareSplitter` pode colocar `network.research` antes desse passo quando a pesquisa é permitida; o resultado da pesquisa chega como dependência e mantém as fontes estruturadas no ciclo. O executor conversacional somente lê contexto, relógio local e evidências autorizadas: não chama provider, não escreve workspace e não executa código.

## 5. Porta 2 — Prompt

É dedicada à criação, análise e refinamento de prompts.

Exemplo:
> “Quero um prompt para criar uma imagem de um carro futurista.”

Papéis especialistas previstos:

```
Porta 2 — PROMPT
├── Prompt Agent
├── Prompt Research
├── Prompt Critic
├── Prompt Optimizer
└── Prompt Library
```

Permissões conceituais:
- conversa sobre prompt ✅
- criação de prompt ✅
- refinamento ✅
- análise ✅
- Web ✅
- criação de projeto ❌
- execução ❌
- APIs externas ❌ agora

A Porta 2 **pode usar Web** para referências, técnicas, informações atuais ou outros dados necessários para construir/refinar o prompt.

Quando o prompt estiver concluído, o fluxo termina. Isso não inicia automaticamente execução ou desenvolvimento. Uma nova ordem do usuário pode abrir outra porta.

Na implementação, a entrega usa `prompt.library.write` (com alias `prompt.library.generate`) e permanece terminal. Follow-ups como “melhore este prompt” continuam em `Door.PROMPT`; `NO_WEB` remove a etapa `network.research` antes da entrega.

## 6. Porta 3 — Criação / Desenvolvimento

É o fluxo completo de construção de software/projetos.

Exemplo:
> “Quero criar um aplicativo de IPTV.”

Papéis especialistas previstos:

```
Porta 3 — CRIAÇÃO
├── Requirements Agent
├── Research Agent
├── Architecture Agent
├── Roadmap Agent
├── Code Agent
├── UI Agent
├── Backend Agent
├── Database Agent
├── Security Agent
├── Test Agent
├── Review / Critic Agent
├── Integration Agent
└── Release Agent
```

Permissões conceituais:
- planejamento ✅
- Web ✅
- pesquisa ✅
- arquitetura ✅
- código ✅
- especialistas ✅
- execução controlada ✅
- testes ✅
- Git conforme autorização ✅
- APIs externas ❌ agora

No futuro, a Porta 3 poderá usar APIs/providers especializados.

Na implementação atual, `CreatePhaseMachine` e `SecretaryState` persistem a sequência de criação e exigem aprovação explícita antes de `APPROVED`. Até esse ponto, `DoorPolicy` bloqueia `workspace.*` e `sandbox.*`. O catálogo bounded declara os especialistas previstos com provenance local, mas não embute provider ou LLM próprio.

Após a aprovação, `TarefaStateMachine` governa prompt, execução, QA, aprovação ou correção. A entrega publica um recibo local e usa `LocalDeliveryPackager` para produzir um ZIP verificável; Git permanece sujeito à autorização do workspace e APIs externas seguem bloqueadas.

## 7. Fluxo da criação

A criação não começa necessariamente com código:

```
CRIAÇÃO
   ↓
Pesquisa
   ↓
Conversa
   ↓
Requisitos
   ↓
Ideias / dúvidas
   ↓
Arquitetura
   ↓
Plano
   ↓
Aprovação do usuário
   ↓
Roadmap
   ↓
Divisão de tarefas
   ↓
Especialistas
   ↓
Prompts especializados
   ↓
Execução
   ↓
Integração
   ↓
Revisão
   ↓
Testes
   ↓
Git / entrega
```

“Quero criar um app” inicia o fluxo de criação, mas conversar sobre o app não significa automaticamente autorizar execução.

Exemplo:
- “Quero fazer um aplicativo de IPTV.” → criação, fase de discussão.
- “Fechado. Pode começar o desenvolvimento.” → criação, desenvolvimento autorizado.

## 8. Futuro uso de APIs

Quando a camada de APIs estiver madura:

```
Brain
 ├── Biblioteca interna
 ├── Web
 └── APIs / Providers
```

O Brain poderá avaliar recursos disponíveis por especialidade.

Exemplo:

```
Projeto
  ↓
Roadmap
  ↓
Tarefas
  ├── Android
  ├── Backend
  ├── Banco
  ├── UI
  ├── Segurança
  └── Testes
       ↓
especialista/provider apropriado
```

O Brain poderá preparar prompts específicos, distribuir tarefas, receber resultados e integrar tudo.

## 9. Revisão e segunda opinião

O Brain não deve simplesmente juntar respostas.

```
Especialista
   ↓
resultado
   ↓
Brain
   ↓
integração
   ↓
revisão
   ↓
testes
```

Se houver erro:
1. identificar o responsável;
2. devolver para correção;
3. validar novamente.

Se a correção não resolver, uma segunda opinião pode ser solicitada a outro especialista/provider adequado.

## 10. Integração, testes e entrega

Depois das tarefas:

```
Tarefas
  ↓
Integração
  ↓
Revisão global
  ↓
Testes
  ↓
E2E
  ↓
Validação
```

Se Git estiver configurado e houver autorização, o fluxo poderá fazer commit/push.

Sem Git, a entrega poderá ser um ZIP acompanhado de resumo.

O resumo deve registrar, quando aplicável:
- o que foi desenvolvido;
- arquitetura;
- funcionalidades;
- testes;
- problemas encontrados;
- problemas corrigidos;
- providers/APIs utilizados;
- estado do Git.

## 11. Relação com o Brain Core

As três portas organizam a entrada e o domínio de trabalho; não substituem o Brain Core.

Fluxo conceitual:

```
Secretário
   ↓
Door / OrderIntent
   ↓
Policy Layer
   ↓
Brain Planner
   ↓
Router
   ↓
Task Engine
   ↓
Context
   ↓
Provider Gateway
   ↓
Specialist
   ↓
Critic
   ↓
Readiness / Learning
```

A Porta 3 utiliza o ciclo completo de desenvolvimento. A Porta 1 é principalmente conversacional/planejamento. A Porta 2 é especializada em prompts.

## 12. Princípios

1. **Três portas, três domínios especializados.**
2. **Web liberada nas três portas.**
3. **APIs externas entram depois, não agora.**
4. **O Secretário encaminha; não executa o trabalho especializado.**
5. **Cada porta possui suas próprias regras e liberações.**
6. **Conversar sobre criação não equivale a autorizar execução.**
7. **Agentes são especializados e obedecem aos contratos do BrainCode.**
8. **O Brain Core continua sendo a autoridade de Policy, planejamento, roteamento, execução autorizada e validação.**
9. **A Porta 2 termina no fluxo de prompt; uma nova ordem pode abrir outra porta.**
10. **A Porta 3 pode evoluir de conversa e pesquisa até desenvolvimento, revisão, testes e entrega.**

## 13. Relação com RooftS

RooftS continua sendo uma única entidade composta pelas camadas 0.3, 0.4, 0.5 e 0.6. As três portas são uma arquitetura de fluxo do BrainCode e não substituem nem redefinem o RooftS.

## 14. Decisão consolidada

> O BrainCode terá um Agente Secretário especializado como ponto inicial de entrada. O Secretário classifica a intenção e encaminha a interação para uma de três portas: Chat/Plano, Prompt ou Criação/Desenvolvimento. Cada porta possui agentes especialistas, regras, permissões e ciclo de vida próprios.
>
> A Web é uma capacidade disponível para as três portas. APIs externas ficam para uma fase posterior, quando o restante do BrainCode estiver consolidado.
>
> O Secretário organiza a ordem e encaminha o trabalho. Os especialistas executam suas responsabilidades dentro das regras da porta e da arquitetura central do BrainCode.


## 15. MeiGen como referência e futura capacidade da Porta 2

Foi identificada a família de projetos **MeiGen AI / MeiGen-AI-Design-MCP** como referência arquitetural relevante para a Porta 2. A decisão não é incorporar o projeto automaticamente agora, mas registrar o que pode casar com a arquitetura do BrainCode quando a Porta 2 for consolidada.

### O que o conceito traz

- criação e refinamento de prompts;
- pesquisa de referências em biblioteca de prompts;
- especialistas/subagentes separados para funções como criação de prompt, pesquisa e geração;
- execução paralela de especialistas quando as tarefas forem independentes;
- Skills e comandos;
- MCP como camada de integração com hosts/agentes;
- geração de imagem e vídeo;
- suporte a providers diferentes;
- possibilidade de usar ComfyUI local;
- possibilidade de providers/API externos posteriormente.

### Encaixe no BrainCode

O MeiGen **não será tratado como um segundo cérebro nem como o dono do fluxo**. O encaixe conceitual é:

```
Brain
  ↓
Secretary
  ↓
Porta 2 — Prompt
  ↓
Prompt Specialist
  ├── Prompt Crafter
  ├── Gallery / Reference Research
  └── Prompt Critic / Optimizer
  ↓
Agent Self-E2E
  ↓
Secretary / Door E2E
  ↓
Provider / Capability
  ├── MeiGen
  ├── ComfyUI local
  └── outros providers futuros
```

A responsabilidade permanece separada:

- **Brain:** decide, planeja e orquestra.
- **Secretário:** controla porta, estado, restrições, dependências e passagem entre etapas.
- **Especialistas:** executam responsabilidades bounded.
- **E2E do especialista:** valida o próprio resultado antes de devolvê-lo.
- **E2E da Porta/Secretário:** valida o resultado antes de liberar a próxima etapa.
- **Provider:** fornece capacidade de execução; não assume o papel de orquestrador.
- **Policy:** continua sendo a autoridade de autorização.

### Provider instalado não significa provider autorizado

Mesmo que componentes MeiGen, MCP, ComfyUI ou APIs existam no código, isso não significa que estejam autorizados a executar.

Nesta fase:

- Web: liberada conforme Policy.
- APIs externas: bloqueadas.
- Providers externos: não ativados por padrão.
- Infraestrutura pode permanecer preparada para ativação futura.

Quando a camada de APIs/providers for aberta, a seleção deverá passar pelo Provider Gateway/Policy e pela especialidade da tarefa.

### Por que a referência pertence principalmente à Porta 2

A composição de prompt, pesquisa de referências, crítica, otimização, biblioteca de prompts e preparação para geração visual são responsabilidades naturais da Porta 2. A geração efetiva por um provider pode ser acionada posteriormente, conforme a ordem do usuário e as permissões.

Exemplo:

```
"Crie um prompt de um foguete decolando no deserto ao pôr do sol."
        ↓
Secretário → PROMPT
        ↓
Prompt Specialist
        ↓
pesquisa/referências (se permitida)
        ↓
Prompt Crafter
        ↓
Prompt Critic / Optimizer
        ↓
Agent E2E
        ↓
Secretary E2E
        ↓
prompt final
```

Se o usuário depois solicitar geração da imagem, isso é uma nova decisão de capability/provider. A conclusão do prompt não deve iniciar automaticamente a geração.

### Regra de incorporação

O BrainCode deve primeiro **auditar** o projeto/referência, verificar licença, código real, testes, segurança e compatibilidade, e somente depois decidir entre:

1. incorporar código sob os termos da licença;
2. adaptar componentes/ideias compatíveis;
3. usar apenas como referência arquitetural.

Não fazer uma reimplementação simplificada de algo maduro sem motivo técnico documentado.

O conceito MeiGen entra, portanto, como **referência da consolidação futura da Porta 2**, sem alterar a regra de que a Porta 1 deve ser consolidada primeiro.
