package com.codeair.mhe.operator.execution

import android.graphics.Rect
import com.codeair.mhe.operator.observation.UiNode
import com.codeair.mhe.operator.observation.UiObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapPostconditionTest {

    private fun node(
        nodeId: String = "g5:p0",
        text: String? = "Tap me",
        contentDescription: String? = null,
        clickable: Boolean = true,
        children: List<UiNode> = emptyList()
    ) = UiNode(
        nodeId = nodeId,
        className = "android.widget.Button",
        text = text,
        contentDescription = contentDescription,
        resourceId = null,
        bounds = Rect(),
        clickable = clickable,
        scrollable = false,
        editable = false,
        children = children
    )

    private fun observation(
        generation: Long,
        packageName: String = "com.example.app",
        root: UiNode? = node(),
        fresh: Boolean = true
    ) = UiObservation(
        observationId = "obs-$generation",
        generation = generation,
        capturedAtMillis = 1000L + generation,
        packageName = packageName,
        root = root,
        fresh = fresh
    )

    private fun rootWithChild(child: UiNode) =
        node(nodeId = "g5:p", children = listOf(child))

    @Test
    fun afterNotFresh_returnsFalse() {
        val before = observation(generation = 5L)
        val after = observation(generation = 6L, fresh = false)
        assertFalse(verifyTapPostcondition(before, after, "g5:p0"))
    }

    @Test
    fun sameGeneration_returnsFalse() {
        val before = observation(generation = 5L)
        val after = observation(generation = 5L)
        assertFalse(verifyTapPostcondition(before, after, "g5:p0"))
    }

    @Test
    fun malformedNodeId_returnsFalse() {
        val before = observation(generation = 5L)
        val after = observation(generation = 6L)
        assertFalse(verifyTapPostcondition(before, after, "no-delimiter-here"))
    }

    @Test
    fun nodeMissingInBefore_returnsFalse() {
        val before = observation(generation = 5L, root = null)
        val after = observation(generation = 6L)
        assertFalse(verifyTapPostcondition(before, after, "g5:p0"))
    }

    @Test
    fun packageChanged_returnsTrue() {
        val before = observation(
            generation = 5L,
            packageName = "com.example.app",
            root = rootWithChild(node())
        )
        val after = observation(
            generation = 6L,
            packageName = "com.other.app",
            root = rootWithChild(node())
        )
        assertTrue(verifyTapPostcondition(before, after, "g5:p0"))
    }

    @Test
    fun nodeAbsentInAfter_returnsTrue() {
        val before = observation(generation = 5L, root = rootWithChild(node()))
        val after = observation(generation = 6L, root = node(nodeId = "g6:p"))
        assertTrue(verifyTapPostcondition(before, after, "g5:p0"))
    }

    @Test
    fun textChanged_returnsTrue() {
        val before = observation(generation = 5L, root = rootWithChild(node(text = "Tap me")))
        val after = observation(generation = 6L, root = rootWithChild(node(text = "Tapped!")))
        assertTrue(verifyTapPostcondition(before, after, "g5:p0"))
    }

    @Test
    fun contentDescriptionChanged_returnsTrue() {
        val before = observation(
            generation = 5L,
            root = rootWithChild(node(text = null, contentDescription = "idle"))
        )
        val after = observation(
            generation = 6L,
            root = rootWithChild(node(text = null, contentDescription = "active"))
        )
        assertTrue(verifyTapPostcondition(before, after, "g5:p0"))
    }

    @Test
    fun findNodeByPath_walksChildIndices() {
        val leaf = node(nodeId = "g5:p0/1")
        val mid = node(nodeId = "g5:p0", children = listOf(node(nodeId = "g5:p0/0"), leaf))
        val root = node(nodeId = "g5:p", children = listOf(mid))
        assertEquals(leaf, findNodeByPath(root, "0/1"))
    }

    @Test
    fun findNodeByPath_invalidIndex_returnsNull() {
        val root = node(children = listOf(node()))
        assertNull(findNodeByPath(root, "7"))
        assertNull(findNodeByPath(root, "abc"))
        assertNull(findNodeByPath(null, "0"))
    }

    @Test
    fun findNodeByPath_emptyPath_returnsRoot() {
        val root = node()
        assertEquals(root, findNodeByPath(root, ""))
    }
}
