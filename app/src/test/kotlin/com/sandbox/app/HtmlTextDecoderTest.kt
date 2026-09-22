package com.sandbox.app

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlTextDecoderTest {
    @Test
    fun `decodifica entidades nomeadas e numericas`() {
        assertEquals("A \"resposta\" & teste 'ok'", HtmlTextDecoder.decode("<b>A &quot;resposta&quot; &amp; teste &#39;ok&#39;&nbsp;</b>"))
    }
}
