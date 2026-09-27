# Licença do LFM2.5-350M

## Fonte verificada

O modelo usado pelo BrainCode é `LiquidAI/LFM2.5-350M-GGUF`, arquivo `LFM2.5-350M-Q4_K_M.gguf`.

Fonte oficial do modelo:
https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF

A página oficial identifica a licença como **LFM Open License v1.0 (`lfm1.0`)**.

## Resultado da revisão

A licença concede direitos perpétuos, mundiais, não exclusivos e sem royalties para reproduzir, modificar, exibir, executar, sublicenciar e distribuir o Work, em forma Source ou Object, desde que as condições da licença sejam cumpridas.

Para distribuição do GGUF dentro de um APK, a seção 4 exige principalmente:

- fornecer uma cópia da licença aos destinatários;
- preservar os avisos de copyright, patente, marca e atribuição aplicáveis;
- preservar o NOTICE quando existir;
- manter os avisos exigidos em derivados distribuídos.

A seção 5 impõe uma limitação específica ao **Commercial Use**: a entidade comercial não pode exceder o **Threshold de US$ 10 milhões de receita anual**. Uso comercial por entidade acima desse limite não é licenciado por esse acordo.

## Decisão para o BrainCode

**Uso/distribuição dentro do APK é permitido pela licença, condicionado às obrigações de redistribuição e ao limite comercial da seção 5.**

O BrainCode deve portanto:

1. distribuir o texto da licença junto ao aplicativo, na área de licenças/terceiros;
2. preservar as atribuições/notices aplicáveis;
3. não declarar que a licença permite uso comercial irrestrito;
4. reavaliar juridicamente o uso antes de qualquer distribuição comercial por entidade que possa exceder o Threshold.

> Esta análise é uma leitura técnica da licença, não aconselhamento jurídico.

## Evidência externa

A página oficial do arquivo mostra SHA-256 e identifica a licença `lfm1.0`. A própria licença define o Threshold como US$ 10 milhões e condiciona o Commercial Use a esse limite.