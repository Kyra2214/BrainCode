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
