// CONFIGURE_LIBRARY: JUnit3
// CONFIGURE_LIBRARY: JUnit4
package testing

import junit.framework.TestCase
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action

abstract class <lineMarker descr="Run Test"><lineMarker descr="*">AbstractSessionTest</lineMarker></lineMarker>: TestCase() {// LIGHT_CLASS_FALLBACK
}

class <lineMarker descr="Run Test">SessionTest</lineMarker>: AbstractSessionTest() { // LIGHT_CLASS_FALLBACK
    fun <lineMarker descr="Run Test">testSessionCreateDelete</lineMarker>() { // LIGHT_CLASS_FALLBACK
    }
}

fun String.foo() {}

class EmptySession
data class TestUserSession(val userId: String, val cart: List<String>)
