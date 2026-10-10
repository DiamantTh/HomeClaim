package systems.diath.homeclaim.platform.paper.command

import com.mojang.brigadier.CommandDispatcher
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.command.CommandSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock

class PaperCommandTreesTest {
    @Test
    fun lifecyclePlotTreeParsesTypedMergeAndUnlinkArguments() {
        val sender = mock(CommandSender::class.java)
        val source = mock(CommandSourceStack::class.java)
        Mockito.`when`(source.sender).thenReturn(sender)
        val calls = mutableListOf<Pair<String, List<String>>>()
        val dispatcher = CommandDispatcher<CommandSourceStack>()
        dispatcher.root.addChild(PaperCommandTrees.plot { _, name, args ->
            calls += name to args
            1
        })

        assertEquals(1, dispatcher.execute("plot merge north", source))
        assertEquals(1, dispatcher.execute("plot unlink true", source))
        assertEquals(1, dispatcher.execute("plot gui", source))
        assertEquals(
            listOf(
                "plot" to listOf("merge", "north"),
                "plot" to listOf("unlink", "true"),
                "plot" to listOf("gui")
            ),
            calls
        )
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException::class.java) {
            dispatcher.execute("plot unlink yes", source)
        }
    }

    @Test
    fun homeclaimLifecycleTreeRoutesGreedySetupArgumentsIntact() {
        val sender = mock(CommandSender::class.java)
        val source = mock(CommandSourceStack::class.java)
        Mockito.`when`(source.sender).thenReturn(sender)
        var routed: List<String>? = null
        val dispatcher = CommandDispatcher<CommandSourceStack>()
        dispatcher.root.addChild(PaperCommandTrees.homeClaim { _, name, args ->
            assertEquals("homeclaim", name)
            routed = args
            1
        })

        dispatcher.execute("homeclaim setup start example plots", source)

        assertEquals(listOf("setup", "start", "example", "plots"), routed)
    }
}
