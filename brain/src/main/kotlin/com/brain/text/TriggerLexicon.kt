package com.brain.text

/**
 * Fonte única de verdade para dicionários de gatilho usados pelas heurísticas
 * determinísticas do Secretário, Planner e respostas de chat. Nenhuma lógica
 * aqui, só listas auditáveis.
 */
object TriggerLexicon {
    // Conversa casual versus intenção real de criar.
    val VERBOS_CRIACAO = listOf(
        "criar", "crie", "cria", "criando", "criação", "criacao",
        "desenvolver", "desenvolva", "desenvolvendo", "desenvolvimento",
        "construir", "construa", "construindo", "construção", "construcao",
        "montar", "monte", "montando", "montagem",
        "programar", "programe", "programando", "programação", "programacao",
        "codar", "code", "codificar", "codifique",
        "implementar", "implemente", "implementando", "implementação", "implementacao",
        "gerar", "gere", "gerando", "geração", "geracao",
        "produzir", "produza", "produzindo", "produção", "producao",
        "elaborar", "elabore", "elaborando", "elaboração", "elaboracao",
        "fazer um app", "fazer um aplicativo", "fazer um sistema", "fazer um site",
        "botar pra rodar", "colocar no ar", "subir o projeto", "publicar o app",
        "lançar", "lance", "lançamento", "deploy", "dar deploy",
        "bootstrapar", "criar do zero", "começar o projeto", "iniciar o projeto",
        "criar projeto", "abrir um projeto"
    )

    val SUBSTANTIVOS_ENTREGAVEL = listOf(
        "aplicativo", "app", "aplicação", "aplicacao", "software", "sistema",
        "site", "página web", "pagina web", "plataforma", "ferramenta",
        "programa", "script", "bot", "api", "serviço", "servico",
        "projeto", "produto digital", "mvp", "protótipo", "prototipo"
    )

    val SINAIS_APROVACAO = listOf(
        "pode começar", "pode comecar", "pode iniciar", "comece", "comece o desenvolvimento",
        "inicie", "inicie o desenvolvimento", "pode desenvolver", "pode implementar",
        "implemente", "execute o plano", "execute os testes", "pode executar",
        "vai", "manda ver", "bora", "partiu", "topo", "confirmado", "confirmo",
        "aprovado", "aprovo", "autorizo", "autorizado", "libera", "liberado",
        "segue o baile", "manda bala", "start", "go", "ok pode fazer", "fechado pode ir",
        "combinado, pode ir", "tá aprovado", "ta aprovado", "sim pode fazer"
    )

    val CONTEXTO_SO_CONVERSA = listOf(
        "estou pensando em", "vamos discutir", "quero discutir", "apenas planejar",
        "só planejar", "so planejar", "tenho uma ideia",
        "quero apenas conversar", "quero só conversar", "quero so conversar",
        "só bater papo", "so bater papo", "apenas bater papo", "só trocar ideia",
        "so trocar ideia", "quero trocar ideia", "bater um papo sobre",
        "quero falar sobre", "quero entender como", "me explique como",
        "me explica como", "como funciona criar", "curiosidade sobre",
        "só por curiosidade", "so por curiosidade", "hipoteticamente",
        "teoricamente", "em teoria", "e se eu quisesse", "no futuro eu quero",
        "mais pra frente eu quero", "mais para frente", "ainda não quero",
        "ainda nao quero", "por enquanto não", "por enquanto nao",
        "sem compromisso", "só de brincadeira", "so de brincadeira",
        "não estou pronto", "nao estou pronto", "só cogitando", "so cogitando",
        "pensando em voz alta", "só uma pergunta sobre", "so uma pergunta sobre"
    )

    val VETOS_EXPLICITOS_REGEX = listOf(
        "(?i)não\\s+quero\\s+(criar|desenvolver|implementar|construir|montar|programar)",
        "(?i)nao\\s+quero\\s+(criar|desenvolver|implementar|construir|montar|programar)",
        "(?i)sem\\s+(criar|desenvolver|implementar|construir)\\s+(agora|ainda|por enquanto)",
        "(?i)não\\s+(agora|ainda)\\b.*\\b(criar|desenvolver|implementar)",
        "(?i)ainda\\s+não\\s+quero\\s+(criar|desenvolver|implementar|construir)"
    )

    // Pesquisa e perguntas factuais que dependem de informação externa.
    val VERBOS_PESQUISA = listOf(
        "pesquisar", "pesquise", "pesquisa", "pesquisando",
        "procurar", "procure", "procura", "buscar", "busque", "busca",
        "verificar", "verifique", "confira", "conferir", "checar", "cheque", "checagem",
        "consultar", "consulte", "consulta", "atualizar", "atualize", "atualização", "atualizacao",
        "descobrir", "descubra", "investigar", "investigue", "investigação", "investigacao",
        "analisar", "analise", "análise", "comparar", "compare", "comparação", "comparacao",
        "encontrar", "encontre", "documentar", "documente", "rastrear", "rastreie",
        "monitorar", "monitore", "acompanhar", "acompanhe", "avaliar", "avalie",
        "informar-se", "se informar", "saber sobre", "quero saber", "me diga", "me fala",
        "me conta", "mostrar", "mostre", "mais atual", "mais recentes", "mudanças recentes", "técnicas atuais"
    )

    val INTERROGATIVOS = listOf(
        "qual", "quais", "quanto", "quanta", "quantos", "quantas", "quando", "onde", "aonde",
        "quem", "por que", "por quê", "porque", "como está", "como esta", "tem como saber"
    )

    val TEMAS_TEMPO_REAL = listOf(
        "temperatura", "clima", "tempo vai fazer", "previsão do tempo", "previsao do tempo",
        "chuva", "vai chover", "sol", "umidade", "vento", "cotação", "cotacao", "dólar", "dolar",
        "euro", "bitcoin", "cripto", "criptomoeda", "ação", "ações", "acao", "acoes", "bolsa de valores",
        "preço", "preco", "valor atual", "quanto custa", "promoção", "promocao", "desconto",
        "placar", "resultado do jogo", "campeonato", "jogo de hoje", "quem ganhou",
        "notícia", "noticia", "notícias", "noticias", "novidade", "manchete",
        "horário de funcionamento", "horario de funcionamento", "está aberto", "esta aberto",
        "funciona hoje", "feriado hoje", "é feriado", "e feriado", "trânsito", "transito",
        "voo", "status do voo", "atraso do voo", "hoje", "agora", "atual", "atualmente",
        "neste momento", "recente", "recentes", "última", "ultima", "últimas", "ultimas", "mais recente"
    )

    val PERGUNTAS_HORA = listOf(
        "que horas", "qual a hora", "qual hora", "horário", "horario", "que horas são", "que horas sao",
        "horas agora", "me diz a hora", "hora atual", "hora certa"
    )

    val PERGUNTAS_DATA = listOf(
        "que dia", "qual a data", "qual data", "data de hoje", "hoje é", "hoje e",
        "que dia é hoje", "que dia e hoje", "dia de hoje", "data atual", "em que dia estamos", "que dia estamos"
    )

    val WEB_TERMS = listOf("pesquis", "internet", "web", "fontes", "referências", "referencias", "google", "buscar na net")
    val EXECUTION_TERMS = listOf("executar", "execute", "execução", "execucao", "rodar", "compilar", "testar", "testes", "rodando")
}
