package com.sandbox.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrainCodeUiSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainChatIsVisible() {
        composeRule.onNodeWithText("BrainCode").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Configurações").assertIsDisplayed()
    }

    @Test
    fun settingsCanBeOpenedAndReturned() {
        composeRule.onNodeWithContentDescription("Configurações").performClick()
        composeRule.onNodeWithText("Configurações do projeto").assertIsDisplayed()
        composeRule.onNodeWithText("Voltar").performClick()
        composeRule.onNodeWithText("BrainCode").assertIsDisplayed()
    }
}
