# BrainCode — Plano de Integração E2E Obrigatório

> Data: 2026-09-21  
> Status: PLANEJADO — não integrado ainda  
> Escopo: todos os agentes, as 3 Portas e o fluxo final do BrainCode  
> Princípio: nenhum agente entrega trabalho sem validar o próprio resultado; nenhuma Porta aceita resultado sem a validação correspondente.

## 1. O que será o E2E do BrainCode

O E2E interno do BrainCode será um mecanismo de validação de trabalho produzido pelo Brain, diferente do CI/GitHub E2E que valida o software.

O E2E interno responde:

> “O resultado produzido atende ao contrato da tarefa, da capacidade e da Porta em que foi executado?”

Ele não será um segundo cérebro e não substituirá o Secretário, a Policy, o Planner ou o Critic.

Fluxo geral:

entrada → Secretário → Porta/estado → especialista → E2E do especialista → Secretário → E2E da Porta → próxima etapa/entrega

Quando houver falha, o E2E deve identificar o requisito/check que falhou e devolver o trabalho ao responsável pela falha, em vez de simplesmente produzir uma resposta incompleta.

## 2. Duas camadas obrigatórias

### 2.1 E2E do agente

Todo agente/especialista utilizado pelo BrainCode deverá possuir um contrato de validação próprio.

O agente produz seu resultado e, antes de devolvê-lo ao Secretário:

1. executa seu E2E;
2. verifica requisitos e restrições;
3. registra evidências;
4. produz um ValidationResult;
5. só entrega ao Secretário se o resultado estiver em PASS.

Se FAIL, o resultado volta para o mesmo agente ou para o especialista explicitamente responsável pelo finding.

**Regra:** agent PASS significa apenas que o agente cumpriu seu próprio contrato. Não significa que o Brain inteiro aceitou o resultado.

### 2.2 E2E da Porta/Brain

Depois do E2E do agente, o Secretário recebe o resultado e aplica a validação correspondente à Porta e à etapa atual.

Assim existe uma segunda barreira:

**Agente → E2E do agente → Secretário → E2E da Porta**

O Secretário pode rejeitar um resultado que o agente considerou válido.

## 3. As 3 Portas terão E2Es diferentes

### Porta 1 — CHAT / CONVERSA

A Porta 1 não deve executar um E2E pesado.

O objetivo é verificar rapidamente se houve uma resposta válida.

Exemplo:

“Qual é a capital do Brasil?”

Checklist mínimo:

- houve resposta;
- resposta não está vazia;
- resposta corresponde à pergunta;
- restrições explícitas foram respeitadas;
- não ocorreu produção/execução/criação não solicitada.

O E2E da Porta 1 deve ser leve e rápido.

Para planejamento, pesquisa ou tarefas mais complexas da Porta 1, o contrato pode aumentar de acordo com a capability, mas não deve transformar conversa simples em uma cadeia pesada de validações.

### Porta 2 — PROMPT

A Porta 2 terá E2E de conteúdo.

Exemplo:

“Crie um prompt de um foguete decolando no deserto ao pôr do sol.”

O contrato deve validar, conforme o tipo de prompt:

- objetivo/tipo do prompt;
- sujeito;
- ação;
- ambiente;
- elementos obrigatórios;
- estilo, quando solicitado ou exigido pelo contrato;
- iluminação;
- composição;
- restrições;
- formato de saída;
- ausência de conteúdo proibido pelo pedido;
- coerência entre requisitos e prompt final.

Se faltar requisito:

**FAIL → Prompt Agent → correção → E2E novamente.**

Concluir um prompt não pode iniciar criação ou execução de projeto.

### Porta 3 — CREATE / DESENVOLVIMENTO

A Porta 3 terá o E2E mais completo.

O fluxo deverá validar progressivamente:

**DISCUSSION → REQUIREMENTS → ARCHITECTURE → PLAN → APPROVED → EXECUTION → INTEGRATION → REVIEW → TESTS → DELIVERY**

Cada etapa terá seu contrato.

Exemplo:

- requisitos: todos os requisitos identificados;
- arquitetura: requisitos cobertos pela arquitetura;
- plano: tarefas rastreáveis aos requisitos;
- execução: artefatos produzidos;
- integração: componentes integrados;
- revisão: findings tratados;
- testes: evidências disponíveis;
- entrega: artefatos verificáveis e resumo.

Não basta o caminho existir no código. O E2E deverá provar a execução real da cadeia.

## 4. Contrato de validação

Criar um conceito comum de contrato, conceitualmente:

ValidationContract

Cada capability/agente define:

- entrada esperada;
- requisitos obrigatórios;
- restrições;
- formato esperado;
- evidências exigidas;
- checks;
- severidade;
- responsável pela correção;
- condição de PASS;
- condição de FAIL.

O resultado deverá ser estruturado, conceitualmente:

ValidationResult

Com pelo menos:

- status: PASS/FAIL;
- capability;
- agente responsável;
- etapa;
- checks executados;
- checks aprovados;
- checks falhos;
- requisitos ausentes;
- evidências;
- responsável pelo retry/correção;
- número de tentativa;
- referência do resultado anterior.

## 5. Nenhum agente fica fora

A integração deverá cobrir **todos os agentes/especialistas que o BrainCode puder selecionar**, não apenas os atuais agentes de pesquisa e código.

O catálogo de especialistas deverá ser auditado e cada definição deverá declarar:

- capability(s);
- entrada;
- saída;
- ValidationContract;
- evidências esperadas;
- E2E;
- agente responsável por correção;
- possibilidade de retry;
- limite de tentativas.

A lista deve acompanhar a evolução do BrainCode. Novos agentes não poderão ser promovidos ao catálogo de execução sem contrato E2E.

Categorias previstas incluem, conforme a necessidade real do Brain:

- Conversation;
- Research;
- Analysis;
- Planning;
- Prompt;
- Prompt Research;
- Prompt Critic;
- Prompt Optimizer;
- Requirements;
- Architecture;
- Roadmap;
- Documentation;
- Code;
- UI;
- Backend;
- Database;
- Security;
- Test;
- Review/Critic;
- Integration;
- Release.

Isso não significa criar agentes duplicados. Agentes existentes devem ser reutilizados e receber contratos E2E.

## 6. Relação com o Secretário

O Secretário será o coordenador das validações, não o executor de todo o trabalho.

Responsabilidades:

1. determinar a Porta;
2. determinar o estado/fase;
3. selecionar o especialista autorizado;
4. receber apenas resultados validados pelo E2E do agente;
5. executar a validação da Porta;
6. aceitar, rejeitar ou devolver;
7. encaminhar finding ao responsável correto;
8. impedir avanço enquanto um requisito obrigatório estiver em FAIL.

O Secretário não deverá “consertar” o resultado produzido por um especialista.

## 6.1 Porta 3 — Roadmap como checklist operacional do Secretário

Na Porta 3, o **Roadmap é o checklist operacional do Secretário**. O CreatePhaseMachine define a macrofase; o Roadmap define o trabalho verificável dentro dela.

Para cada etapa/tarefa do Roadmap, o Secretário deverá acompanhar:

- responsável;
- dependências;
- requisitos;
- restrições;
- evidências esperadas;
- E2E do especialista;
- resultado da validação do Secretário;
- status;
- tentativa atual;
- finding, quando houver;
- responsável pela correção;
- bloqueios;
- condição para liberar a próxima etapa.

Uma etapa só poderá ser marcada como concluída quando houver evidência e PASS do E2E correspondente. O agente não poderá avançar o Roadmap por conta própria.

O Roadmap poderá liberar tarefas em paralelo quando as dependências permitirem. Tarefas dependentes ficam bloqueadas até que seus pré-requisitos tenham PASS.

Fluxo:

**Roadmap → tarefa → especialista → Self-E2E → Secretário → E2E da etapa → PASS → próxima tarefa**

Em FAIL:

**FAIL → finding → responsável → correção → Self-E2E novamente → Secretário → E2E da etapa novamente**

Assim, a Porta 3 não depende de uma sequência implícita ou de agentes reportando “concluído”; o Secretário possui um estado verificável de cada etapa.

## 6.2 Especialista de Documentação

O BrainCode deverá tratar **Documentação** como um especialista próprio quando a tarefa exigir produção ou atualização documental.

O especialista de Documentação:

- recebe decisões, requisitos, mudanças e evidências validadas;
- atualiza a documentação correspondente;
- mantém arquitetura, decisões, APIs/interfaces, build, testes, changelog e entrega coerentes com o estado real;
- não inventa funcionalidades ou evidências;
- executa seu próprio E2E antes de devolver o resultado ao Secretário.

A Documentação é um especialista transversal, não uma responsabilidade integral do Secretário. O Secretário coordena quando uma atualização documental é necessária e valida o resultado.

Na entrega final da Porta 3, a documentação deverá ser verificada como parte do checklist do Roadmap:

**implementação → evidências → documentação atualizada → E2E da documentação → validação do Secretário**

O especialista de Documentação também entra na regra permanente de que nenhum agente pode ser promovido ao catálogo sem ValidationContract e E2E.

## 7. Relação com Policy

O E2E não substitui Policy.

A ordem de autoridade permanece:

**Policy → Secretário/Porta → capability → agente → E2E**

O fato de um E2E passar nunca concede uma capability que a Policy negou.

Exemplo:

NO_WEB continua bloqueando Web mesmo que um agente de pesquisa tenha um E2E válido.

CHAT continua sem sandbox.code, mesmo que algum agente produza código.

APPROVED continua obrigatório antes de execução da Porta 3.

## 8. Relação com o PostExecutionGate

O novo E2E deve aproveitar a infraestrutura existente em vez de criar um segundo sistema paralelo.

Integração prevista:

**ExecutionEvidence → VerificationCheck → E2E/ValidationContract → UniversalCritic → Revision/Fix → Readiness → ValidatedLearning**

Quando houver infraestrutura equivalente já existente, ela deverá ser reutilizada.

Objetivo: consolidar a validação, não multiplicar mecanismos concorrentes.

## 9. Ciclo de falha e correção

Um FAIL deve ser rastreável.

Exemplo:

Prompt Agent
→ produz prompt
→ E2E detecta STYLE_MISSING
→ ValidationResult(FAIL)
→ Secretário identifica responsável Prompt Agent
→ agente corrige
→ E2E executa novamente
→ PASS
→ Secretário valida Porta 2
→ entrega.

Para código:

Code Agent
→ implementação
→ E2E detecta teste quebrado
→ finding retorna ao responsável
→ correção
→ build/test
→ E2E
→ revisão
→ PASS.

Se o limite de tentativas for atingido, o Brain deve parar e informar o problema em vez de fabricar um PASS.

## 10. Evidências

PASS precisa ser sustentado por evidência.

Dependendo da capability, podem ser:

- texto produzido;
- campos estruturados;
- fontes;
- hashes;
- arquivos;
- resultado de testes;
- build;
- screenshots;
- logs;
- estado de execução;
- artefatos de entrega;
- provenance.

Um PASS sem evidência verificável não deve ser tratado como PASS de produção.

## 11. Níveis de E2E

Para evitar latência desnecessária, o Brain terá níveis de validação.

### E2E-0 — Sanidade

Para conversas simples.

Resposta existente, coerente com a entrada e sem violação de restrições.

### E2E-1 — Capability

Para capacidades normais.

Contrato + checklist + evidência.

### E2E-2 — Agente

Validação completa do especialista antes da entrega ao Secretário.

### E2E-3 — Porta

Validação independente do resultado recebido pelo Secretário.

### E2E-4 — Produto/Fluxo

Para criação e desenvolvimento.

Validação ponta a ponta de toda a cadeia.

O nível será determinado pela capability, Porta e complexidade, e não aplicado indiscriminadamente a toda mensagem.

## 12. Ordem de integração

A implementação deverá ser sequencial.

### Fase E2E-1 — Contratos

- definir ValidationContract;
- definir ValidationResult;
- definir evidências;
- definir severidade;
- definir retry;
- definir responsável pelo finding;
- mapear capabilities existentes.

### Fase E2E-2 — Infraestrutura

- criar runner/engine de validação;
- integrar com ExecutionEvidence;
- integrar com VerificationCheck;
- integrar com UniversalCritic;
- integrar com Revision/Fix;
- integrar com Readiness;
- persistir resultado quando necessário.

### Fase E2E-3 — Agentes

Migrar todos os agentes existentes.

Nenhum agente permanece “sem E2E”.

Cada agente precisa de:

Agent → Execute → Self-E2E → ValidationResult → Secretary

### Fase E2E-4 — Porta 1

Implementar E2E leve de conversa e contratos específicos para planejamento/pesquisa quando necessário.

Validar especialmente:

- resposta;
- contexto;
- restrições;
- ausência de produção;
- ausência de execução.

### Fase E2E-5 — Porta 2

Implementar E2E de prompt.

Validar:

- prompt de texto;
- prompt de imagem;
- prompt de código;
- requisitos;
- restrições;
- follow-up;
- encerramento terminal.

### Fase E2E-6 — Porta 3

Implementar E2E de desenvolvimento.

Validar cada fase e depois o fluxo completo:

**requisitos → arquitetura → plano → aprovação → execução → integração → revisão → testes → entrega**

### Fase E2E-7 — E2E final do Brain

Criar journeys completos das 3 Portas.

Os journeys deverão provar:

- classificação correta;
- seleção do agente;
- E2E do agente;
- retorno ao Secretário;
- E2E da Porta;
- correção após FAIL;
- PASS final;
- respeito à Policy;
- ausência de vazamento entre Portas.

## 13. Testes obrigatórios

### Contratos
- ValidationContractTest
- ValidationResultTest
- ValidationEvidenceTest

### Agentes
- um teste E2E por agente/capability;
- agente que produz resultado inválido;
- agente que corrige após FAIL;
- retry limitado;
- FAIL definitivo.

### Secretário
- rejeita resultado sem E2E;
- usa o Roadmap como checklist operacional da Porta 3;
- bloqueia etapa enquanto dependência obrigatória estiver sem PASS;
- permite paralelismo somente quando as dependências estiverem satisfeitas;
- rejeita E2E sem evidência;
- encaminha finding ao responsável;
- não corrige pelo agente;
- impede avanço com FAIL obrigatório.

### Porta 1
- pergunta simples → resposta;
- restrição → respeitada;
- conversa não executa;
- conversa não cria projeto.

### Porta 2
- prompt completo → PASS;
- requisito ausente → FAIL;
- correção → PASS;
- conclusão não inicia criação.

### Porta 3
- requisito → arquitetura;
- arquitetura → plano;
- Roadmap criado e usado como checklist;
- tarefas paralelas respeitam dependências;
- Documentação atualizada por especialista quando exigida;
- E2E da documentação;
- aprovação;
- execução;
- integração;
- revisão;
- testes;
- entrega;
- falha devolvida ao especialista correto.

## 14. Critério de consolidação

O sistema só será considerado integrado quando:

- todos os agentes ativos tiverem ValidationContract;
- todos os agentes ativos tiverem E2E;
- Secretário rejeitar resultado sem validação;
- cada Porta tiver seu contrato E2E;
- FAIL retornar ao responsável correto;
- correção gerar nova validação;
- limite de retry funcionar;
- evidências forem persistidas/transportadas;
- Policy continuar sendo autoridade;
- nenhuma Porta vazar para outra;
- CI verde;
- testes unitários/integrados verdes;
- E2E interno verde;
- UI E2E verde;
- readiness verde;
- evidência real no HEAD.

## 15. Regra permanente para novos agentes

Depois da integração:

> **Novo agente sem E2E não entra no catálogo de execução do BrainCode.**

Caminho obrigatório:

**Definição → Capability → ValidationContract → E2E → testes → catálogo → execução**

Isso transforma a validação E2E em parte da arquitetura do BrainCode, e não em uma ferramenta opcional de teste.

## 16. Resultado arquitetural esperado

A arquitetura final passa a ser:

**Usuário → Secretário → Porta → Especialista → E2E do Especialista → Secretário → E2E da Porta → próxima etapa/entrega**

Na Porta 3:

**Especialista → E2E → Secretário → E2E da etapa → próximo especialista → ... → E2E final do produto**

O BrainCode deixa de aceitar apenas “respostas produzidas” e passa a aceitar **resultados produzidos + validados + evidenciados**.
