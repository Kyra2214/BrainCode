package com.sandbox.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeGenerationExecutorTest {
    @Test
    fun `parser separa multiplos arquivos e preserva conteudo`() {
        val files = CodeGenerationExecutor.parseFiles(
            """
            ```package.json
            {"scripts":{"build":"npm test"}}
            ```
            ```src/main.js
            console.log("ok");
            ```
            """.trimIndent()
        )
        assertEquals(listOf("package.json", "src/main.js"), files.map { it.first })
        assertTrue(files[1].second.contains("console.log"))
    }

    @Test
    fun `parser ignora texto fora de blocos`() {
        val files = CodeGenerationExecutor.parseFiles("explicação\n```README.md\n# app\n```")
        assertEquals(1, files.size)
        assertEquals("README.md", files.single().first)
    }
}
