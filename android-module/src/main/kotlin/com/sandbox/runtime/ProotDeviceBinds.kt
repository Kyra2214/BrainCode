package com.sandbox.runtime

import java.io.File

/**
 * Binds curados para /dev, /proc e /sys dentro do `proot`.
 *
 * `proot` não isola nada em nível de kernel (não é um namespace real): tudo
 * que é "bindado" aqui continua sendo, de fato, o nó do host visto de
 * dentro do processo sandboxed. Antes, o launcher fazia `-b /dev`, `-b
 * /proc` e `-b /sys` — a árvore inteira — o que expõe nós como
 * `/dev/block/<qualquer>`, `/dev/input/<qualquer>`, `/dev/kmsg`, `/dev/binder`,
 * `/dev/graphics/<qualquer>` (informação de hardware do dispositivo real e acesso a
 * drivers do host) sem nenhum motivo funcional: python3/node/bash não usam
 * nada disso.
 *
 * Esta classe reduz a superfície para o que esses runtimes realmente usam.
 *
 * Limitações conhecidas, aceitas nesta fase (não escondidas):
 * - `/proc` continua bindado por inteiro. python3, node e a maioria dos
 *   runtimes modernos leem `/proc/self/<arquivos>` (maps, exe, fd) na inicialização
 *   e quebram sem isso, e o `proot` não tem como sintetizar um
 *   `/proc/self` plausível sozinho. Dentro do sandbox ainda é possível
 *   listar processos do host e ler `/proc/meminfo`, `/proc/version` etc.
 *   Uma correção completa exige um caminho com isolamento de kernel real
 *   (namespaces de PID/mount), que o `proot` não fornece — ver
 *   docs/proot-noexec-strategy.md e AUDITORIA_PESADA.md.
 * - `/sys` não é bindado por padrão: é quase inteiramente introspecção de
 *   hardware/kernel do host, e nenhum dos runtimes hoje suportados falha
 *   sem ele. Pode ser habilitado pontualmente via [includeSys] se um
 *   comando específico precisar.
 */
object ProotDeviceBinds {
    /** Nós de /dev que python3/node/bash precisam para funcionar normalmente. */
    private val DEV_NODES = listOf(
        "/dev/null", "/dev/zero",
        "/dev/random", "/dev/urandom",
        "/dev/tty", "/dev/ptmx"
    )

    /**
     * Retorna os argumentos `-b <origem>` para os nós de /dev existentes no
     * host e para `/proc` (árvore inteira — ver limitação acima). `/sys`
     * fica de fora a menos que [includeSys] seja true.
     */
    fun bindArgs(includeSys: Boolean = false): List<String> = buildList {
        DEV_NODES.filter { File(it).exists() }.forEach { node -> add("-b"); add(node) }
        add("-b"); add("/proc")
        if (includeSys) { add("-b"); add("/sys") }
    }
}
