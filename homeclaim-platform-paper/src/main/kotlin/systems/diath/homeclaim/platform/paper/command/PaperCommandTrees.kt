package systems.diath.homeclaim.platform.paper.command

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
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

        listOf("claim", "c", "auto", "a", "info", "i", "list", "l", "gui", "menu", "clear", "sethome", "delete", "unclaim", "confirm")
            .forEach { name ->
                root.then(Commands.literal(name).executes { context -> dispatch(context.source.sender, "plot", listOf(name)) })
            }

        root.then(Commands.literal("reset")
            .executes { context -> dispatch(context.source.sender, "plot", listOf("reset")) }
            .then(Commands.literal("confirm")
                .executes { context -> dispatch(context.source.sender, "plot", listOf("reset", "confirm")) }))

        root.then(Commands.literal("backup").then(
            Commands.literal("list").executes { context -> dispatch(context.source.sender, "plot", listOf("backup", "list")) }
        ))
        root.then(Commands.literal("restore").then(
            Commands.argument("backupId", StringArgumentType.word())
                .executes { context -> dispatch(context.source.sender, "plot", listOf("restore", StringArgumentType.getString(context, "backupId"))) }
        ))

        listOf("copy", "move", "swap").forEach { operation ->
            root.then(Commands.literal(operation).then(
                Commands.argument("targetRegionId", StringArgumentType.word())
                    .executes { context ->
                        dispatch(context.source.sender, "plot", listOf(operation, StringArgumentType.getString(context, "targetRegionId")))
                    }
            ))
        }

        listOf("home", "h").forEach { name ->
            root.then(Commands.literal(name)
                .executes { context -> dispatch(context.source.sender, "plot", listOf(name)) }
                .then(Commands.argument("plot", StringArgumentType.word())
                    .executes { context ->
                        dispatch(
                            context.source.sender,
                            "plot",
                            listOf(name, StringArgumentType.getString(context, "plot"))
                        )
                    }
                )
            )
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
                    .then(Commands.argument("plot", StringArgumentType.word())
                        .executes { context ->
                            dispatch(
                                context.source.sender,
                                "plot",
                                listOf(
                                    name,
                                    StringArgumentType.getString(context, "player"),
                                    StringArgumentType.getString(context, "plot")
                                )
                            )
                        }
                    )
            ))
        }

        listOf("add", "trust", "deny", "remove").forEach { name ->
            root.then(Commands.literal(name).then(
                Commands.argument("player", StringArgumentType.word())
                    .suggests { _, builder ->
                        Bukkit.getOnlinePlayers().asSequence().map { it.name }
                            .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                            .forEach(builder::suggest)
                        builder.buildFuture()
                    }
                    .executes { context -> dispatch(context.source.sender, "plot", listOf(name, StringArgumentType.getString(context, "player"))) }
            ))
        }

        root.then(Commands.literal("biome").then(
            Commands.argument("biome", StringArgumentType.greedyString())
                .suggests { _, builder ->
                    RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).asSequence().map { it.key.toString() }
                        .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                        .forEach(builder::suggest)
                    builder.buildFuture()
                }
                .executes { context -> dispatch(context.source.sender, "plot", listOf("biome", StringArgumentType.getString(context, "biome"))) }
        ))

        root.then(Commands.literal("rate").then(
            Commands.argument("value", StringArgumentType.word())
                .suggests { _, builder ->
                    (1..5).map(Int::toString).filter { it.startsWith(builder.remaining) }.forEach(builder::suggest)
                    builder.buildFuture()
                }
                .executes { context -> dispatch(context.source.sender, "plot", listOf("rate", StringArgumentType.getString(context, "value"))) }
        ))

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
                .then(jobIdAction("inspect", name, dispatch))
                .then(jobRecoveryAction("recover", name, dispatch))
                .then(jobRecoveryAction("resolve", name, dispatch))
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

    private fun jobIdAction(
        action: String,
        commandName: String,
        dispatch: (CommandSender, String, List<String>) -> Int
    ): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(action)
            .then(Commands.argument("jobId", StringArgumentType.word())
                .executes { context ->
                    dispatch(context.source.sender, "plot", listOf(
                        commandName,
                        action,
                        StringArgumentType.getString(context, "jobId")
                    ))
                }
            )

    private fun jobRecoveryAction(
        action: String,
        commandName: String,
        dispatch: (CommandSender, String, List<String>) -> Int
    ): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(action)
            .then(Commands.argument("jobId", StringArgumentType.word())
                .then(Commands.argument("recoveryAction", StringArgumentType.word())
                    .suggests { _, builder ->
                        val choices = if (action == "resolve") listOf("acknowledge")
                            else listOf("restore-target", "restore-both")
                        choices.filter { it.startsWith(builder.remaining, ignoreCase = true) }.forEach(builder::suggest)
                        builder.buildFuture()
                    }
                    .executes { context ->
                        dispatch(context.source.sender, "plot", listOf(
                            commandName, action, StringArgumentType.getString(context, "jobId"),
                            StringArgumentType.getString(context, "recoveryAction")
                        ))
                    }
                )
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
