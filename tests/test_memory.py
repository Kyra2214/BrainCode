import tempfile
import unittest
from pathlib import Path
from brain_runtime.memory import Experience, SQLiteExperienceMemory


class MemoryTests(unittest.TestCase):
    def test_experience_survives_restart_and_preserves_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "memory.sqlite3"
            memory = SQLiteExperienceMemory(path)
            memory.record(Experience("exp-1", "run-1", "task-1", "lead research", "web", "waterfall", "ok", .9, provenance=("source-a",)))
            memory.close()
            restored = SQLiteExperienceMemory(path)
            found = restored.search("lead")
            self.assertEqual(len(found), 1)
            self.assertEqual(found[0].provenance, ("source-a",))
            self.assertEqual(found[0].quality, .9)
            restored.close()


if __name__ == "__main__":
    unittest.main()
