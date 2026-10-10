package systems.diath.homeclaim.platform.paper.command

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

/** Paper Brigadier trees. The runtime router owns service lookup and result presentation. */
internal object PaperCommandTrees {
    fun homeClaim(dispatch: (CommandSender, String, List<String>) -> Int): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("homeclaim")
            .executes { context -> dispatch(context.source.sender, "homeclaim", emptyList()) }
            .then(Commands.literal("reload").executes { context -> dispatch(context.source.sender, "homeclaim", listOf("reload")) })
            .then(Commands.literal("migrate").executes { context -> dispatch(context.source.sender, "homeclaim", listOf("migrate")) })
            .then(greedy("setup") { source, args -> dispatch(source, "homeclaim", args) })
            .then(greedy("plot") { source, args -> dispatch(source, "homeclaim", args) })
            .then(greedy("entryforce") { source, args -> dispatch(source, "homeclaim", args) })
            .then(greedy("audit") { source, args -> dispatch(source, "homeclaim", args) })
            .build()

    fun plot(dispatch: (CommandSender, String, List<String>) -> Int): LiteralCommandNode<CommandSourceStack> {
        val root = Commands.literal("plot")
            .executes { context -> dispatch(context.source.sender, "plot", emptyList()) }

        listOf("claim", "c", "auto", "a", "home", "h", "info", "i", "list", "l", "gui", "menu", "reset", "clear")
            .forEach { name ->
                root.then(Commands.literal(name).executes { context -> dispatch(context.source.sender, "plot", listOf(name)) })
            }

        listOf("visit", "v").forEach { name ->
            root.then(Commands.literal(name).then(
                Commands.argument("player", StringArgumentType.word())
                    .suggests { _, builder ->
                        Bukkit.getOnlinePlayers().asSequence().map { it.name }
                            .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                            .forEach(builder::suggest)
                        builder.buildFuture()
                    }
                    .executes { context ->
                        dispatch(context.source.sender, "plot", listOf(name, StringArgumentType.getString(context, "player")))
                    }
            ))
        }

        listOf("merge", "m").forEach { name ->
            root.then(Commands.literal(name)
                .executes { context -> dispatch(context.source.sender, "plot", listOf(name)) }
                .then(Commands.argument("direction", StringArgumentType.word())
                    .suggests { _, builder ->
                        listOf("all", "north", "east", "south", "west")
                            .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                            .forEach(builder::suggest)
                        builder.buildFuture()
                    }
                    .executes { context ->
                        dispatch(context.source.sender, "plot", listOf(name, StringArgumentType.getString(context, "direction")))
                    }
                )
            )
        }

        listOf("unlink", "u", "unmerge").forEach { name ->
            root.then(Commands.literal(name)
                .executes { context -> dispatch(context.source.sender, "plot", listOf(name)) }
                .then(Commands.argument("restoreRoads", BoolArgumentType.bool())
                    .executes { context ->
                        dispatch(context.source.sender, "plot", listOf(name, BoolArgumentType.getBool(context, "restoreRoads").toString()))
                    }
                )
            )
        }

        listOf("alias", "desc", "description").forEach { name ->
            root.then(Commands.literal(name)
                .executes { context -> dispatch(context.source.sender, "plot", listOf(name)) }
                .then(Commands.argument("value", StringArgumentType.greedyString())
                    .executes { context ->
                        dispatch(context.source.sender, "plot", listOf(name, StringArgumentType.getString(context, "value")))
                    }
                )
            )
        }

        listOf("jobs", "job").forEach { name ->
            val jobs = Commands.literal(name)
                .executes { context -> dispatch(context.source.sender, "plot", listOf(name)) }
                .then(jobAction("status", name, dispatch))
                .then(jobAction("cancel", name, dispatch))
                .then(jobAction("stop", name, dispatch))
            root.then(jobs)
        }

        return root.build()
    }

    fun zone(dispatch: (CommandSender, String, List<String>) -> Int): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("zone")
            .executes { context -> dispatch(context.source.sender, "zone", emptyList()) }
            .then(Commands.literal("info").executes { context -> dispatch(context.source.sender, "zone", listOf("info")) })
            .build()

    private fun jobAction(
        action: String,
        commandName: String,
        dispatch: (CommandSender, String, List<String>) -> Int
    ): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(action)
            .executes { context -> dispatch(context.source.sender, "plot", listOf(commandName, action)) }
            .then(Commands.argument("world", StringArgumentType.word())
                .suggests { _, builder ->
                    Bukkit.getWorlds().asSequence().map { it.name }
                        .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                        .forEach(builder::suggest)
                    builder.buildFuture()
                }
                .executes { context ->
                    dispatch(context.source.sender, "plot", listOf(commandName, action, StringArgumentType.getString(context, "world")))
                }
            )

    private fun greedy(
        name: String,
        dispatch: (CommandSender, List<String>) -> Int
    ): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name)
            .executes { context -> dispatch(context.source.sender, listOf(name)) }
            .then(Commands.argument("arguments", StringArgumentType.greedyString())
                .executes { context ->
                    val raw = StringArgumentType.getString(context, "arguments")
                    val tail = raw.split(Regex("\\s+")).filter(String::isNotBlank)
                    dispatch(context.source.sender, listOf(name) + tail)
                }
            )
}
