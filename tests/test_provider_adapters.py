import unittest
from brain_runtime.provider_adapters import GeminiAdapter, OpenAICompatibleAdapter, ProviderRequest

class Transport:
    def __init__(self, status=200, body=None): self.status, self.body = status, body or {}
    def post(self, url, *, headers, json, timeout): return self.status, self.body

class ProviderAdapterTests(unittest.TestCase):
    def test_openai_shape_and_credential_reference(self):
        adapter = OpenAICompatibleAdapter(Transport(body={"choices": [{"message": {"content": "ok"}}]}), "https://example")
        response = adapter.complete(ProviderRequest("m", "hello", "cred"), "secret")
        self.assertEqual(response.text, "ok")
    def test_gemini_shape(self):
        adapter = GeminiAdapter(Transport(body={"candidates": [{"content": {"parts": [{"text": "ok"}]}}]}), "https://example")
        self.assertEqual(adapter.complete(ProviderRequest("m", "hello", "cred"), "secret").text, "ok")
    def test_rate_limit_is_typed_as_failure(self):
        with self.assertRaisesRegex(RuntimeError, "429"):
            OpenAICompatibleAdapter(Transport(429), "https://example").complete(ProviderRequest("m", "hello", "cred"), "secret")

if __name__ == "__main__": unittest.main()
