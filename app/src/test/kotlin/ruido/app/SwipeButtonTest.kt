package ruido.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ruido.app.ui.SwipeButton

/** Starting and ending the night must take a deliberate slide, never a touch. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class SwipeButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private var done = 0

    private fun show() = compose.setContent { SwipeButton("Deslizá", { done++ }, "swipe") }

    @Test
    fun touchDoesNothing() {
        show()
        compose.onNodeWithTag("swipe").performTouchInput { down(centerLeft); up() }
        compose.waitForIdle()
        assertEquals(0, done)
    }

    @Test
    fun halfSlideDoesNothing() {
        show()
        compose.onNodeWithTag("swipe").performTouchInput { swipeRight(startX = left + 40f, endX = centerX, durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(0, done)
    }

    @Test
    fun fullSlideActsOnce() {
        show()
        compose.onNodeWithTag("swipe").performTouchInput { swipeRight(startX = left + 40f, endX = right, durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(1, done)
    }

    @Test
    fun screenReadersCanActivateIt() {
        show()
        compose.onNodeWithTag("swipe").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, done)
    }
}
