package com.brain.skill

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RooftsSkillEvaluationTest {
    @Test
    fun `avaliador passa nos casos versionados contra as Skills reais`() {
        val root = JSONObject(File("src/test/resources/catalog/roofts-skill-evaluation-cases.json").readText())
        val casesJson = root.getJSONArray("cases")
        val cases = (0 until casesJson.length()).map { index ->
            val item = casesJson.getJSONObject(index)
            RooftsSkillEvaluationCase(
                id = item.getString("id"),
                objective = item.getString("objective"),
                expectedSkillIds = item.getJSONArray("expectedSkillIds").strings(),
                excludedSkillIds = item.getJSONArray("excludedSkillIds").strings()
            )
        }
        val skills = loadSkills()
        val summary = RooftsSkillEvaluator.evaluate(skills, cases)

        assertEquals(25, skills.size)
        assertTrue("score=${summary.score}, falhas=${summary.results.filterNot { it.passed }}", summary.score >= root.getDouble("minimumScore"))
        assertTrue("casos falhos=${summary.results.filterNot { it.passed }}", summary.results.all { it.passed })
    }

    private fun loadSkills(): List<RooftsSkill> = sequenceOf(
        File("app/src/main/assets/roofts/0.6/skills"),
        File("../app/src/main/assets/roofts/0.6/skills")
    ).firstOrNull(File::isDirectory)?.let { base ->
        val overlayFile = sequenceOf(
            File("app/src/main/assets/roofts/0.6/braincode-skill-overlay.json"),
            File("../app/src/main/assets/roofts/0.6/braincode-skill-overlay.json")
        ).firstOrNull(File::isFile)
        val overlay = overlayFile?.let { file ->
            val items = JSONObject(file.readText()).optJSONArray("skills")
            (0 until (items?.length() ?: 0)).associate { index ->
                val item = items!!.getJSONObject(index)
                val triggers = item.optJSONArray("triggers")
                item.getString("id") to (0 until (triggers?.length() ?: 0)).map { triggers!!.getString(it) }.toSet()
            }
        }.orEmpty()
        base
        .listFiles { file -> file.isDirectory }
        .orEmpty()
        .mapNotNull { directory ->
            val source = File(directory, "SKILL.md")
            val content = source.takeIf(File::isFile)?.readText() ?: return@mapNotNull null
            val parts = content.split("---", limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val frontmatter = parts[1]
            val id = scalar(frontmatter, "name") ?: directory.name
            val description = scalar(frontmatter, "description") ?: return@mapNotNull null
            RooftsSkill(
                id = id,
                description = description,
                body = parts[2].trim(),
                triggers = overlay[id].orEmpty(),
                contentHash = sha256(content),
                sourcePath = source.path
            )
        }
        .sortedBy { it.id }
    }.orEmpty()

    private fun scalar(frontmatter: String, key: String): String? = frontmatter.lines()
        .firstOrNull { it.trimStart().startsWith("$key:") }
        ?.substringAfter(":")
        ?.trim()
        ?.trim('"', '\'')
        ?.ifBlank { null }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun org.json.JSONArray.strings(): Set<String> =
        (0 until length()).map { getString(it) }.toSet()
}
