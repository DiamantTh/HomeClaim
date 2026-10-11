package systems.diath.homeclaim.platform.paper.command

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.contains
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito
import systems.diath.homeclaim.core.model.Bounds
import systems.diath.homeclaim.core.model.Region
import systems.diath.homeclaim.core.model.RegionId
import systems.diath.homeclaim.core.model.RegionRoles
import systems.diath.homeclaim.core.model.RegionOwnership
import systems.diath.homeclaim.core.model.RegionShape
import systems.diath.homeclaim.core.model.AreaId
import systems.diath.homeclaim.core.model.Area
import systems.diath.homeclaim.core.model.AreaScope
import systems.diath.homeclaim.core.model.WorldId
import systems.diath.homeclaim.core.service.RegionService
import systems.diath.homeclaim.core.service.RegionOperationLimitService
import systems.diath.homeclaim.core.service.InMemoryWorldAreaService
import systems.diath.homeclaim.core.service.AuditEntry
import systems.diath.homeclaim.core.service.AuditService
import systems.diath.homeclaim.core.service.AuditStatus
import systems.diath.homeclaim.core.store.InMemoryRegionStore
import systems.diath.homeclaim.platform.paper.gui.GuiManager
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationService
import systems.diath.homeclaim.core.mutation.MutationReason
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetService
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetCompletion
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotResetCompletionStatus
import systems.diath.homeclaim.platform.paper.plot.mutation.persistentJobStatus
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationJobStatus
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationJobRecord
import systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationJobStore
import systems.diath.homeclaim.core.policy.CorrelationId
import java.time.Instant
import systems.diath.homeclaim.platform.paper.util.CommandRateLimiter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class PlotCommandTest {

    @BeforeEach
    fun resetLimiter() {
        CommandRateLimiter.resetAll()
    }

    @Test
    fun `claim still works when no economy service is wired`() {
        val regionService = InMemoryRegionStore()
        val guiManager = mock(GuiManager::class.java)
        val auditService = mock(AuditService::class.java)
        val player = mock(Player::class.java)
        val world = mock(World::class.java)

        val playerId = UUID.randomUUID()
        val regionId = RegionId(UUID.randomUUID())
        val location = Location(world, 10.0, 65.0, 10.0)
        val region = Region(
            id = regionId,
            world = "plots",
            shape = RegionShape.CUBOID,
            bounds = Bounds(0, 15, 0, 255, 0, 15),
            owner = RegionOwnership.UNCLAIMED_OWNER_ID,
            roles = RegionRoles(),
            flags = emptyMap(),
            limits = emptyMap(),
            metadata = emptyMap(),
            mergeGroupId = null,
            price = 0.0
        )
        regionService.createRegion(region, region.bounds)

        doReturn(false).`when`(player).isOp
        doReturn(playerId).`when`(player).uniqueId
        doReturn(location).`when`(player).location
        doReturn(true).`when`(player).hasPermission(anyString())
        doReturn("plots").`when`(world).name

        val plotCommand = PlotCommand(regionService, guiManager, econService = null, auditService = auditService)

        plotCommand.execute(player, arrayOf("claim"))

        assertEquals(playerId, regionService.getRegionById(regionId)?.owner)
        verify(player, never()).sendMessage(contains("economy"))
        val records = Mockito.mockingDetails(auditService).invocations
            .mapNotNull { it.arguments.firstOrNull() as? AuditEntry }
        assertEquals(3, records.size)
        assertEquals(
            listOf(AuditStatus.ATTEMPTED, AuditStatus.ALLOWED, AuditStatus.COMPLETED),
            records.map { it.status }
        )
        assertEquals(1, records.map { it.correlationId }.distinct().size)
        assertEquals("homeclaim:region_claim", records.first().actionKey?.value)
    }

    @Test
    fun `home refuses to choose an arbitrary plot when owner has several`() {
        val regionService = InMemoryRegionStore()
        val player = mock(Player::class.java)
        val playerId = UUID.randomUUID()
        val plotIds = listOf(
            RegionId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
            RegionId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        )
        doReturn(false).`when`(player).isOp
        doReturn(playerId).`when`(player).uniqueId
        doReturn(true).`when`(player).hasPermission(anyString())
        plotIds.forEach { id ->
            regionService.createRegion(
                Region(
                    id = id,
                    world = "plots",
                    shape = RegionShape.CUBOID,
                    bounds = Bounds(0, 15, 0, 255, 0, 15),
                    owner = playerId
                )
            )
        }

        PlotCommand(regionService, mock(GuiManager::class.java), econService = null)
            .execute(player, arrayOf("home"))

        val messages = Mockito.mockingDetails(player).invocations
            .filter { it.method.name == "sendMessage" }
            .mapNotNull { it.arguments.firstOrNull() as? String }
        assertTrue(messages.any { it.contains("multiple plots", ignoreCase = true) || it.contains("mehrere Plots", ignoreCase = true) }, "Unexpected messages: $messages")
        assertTrue(messages.any { it.contains("00000000-0000-0000-0000-000000000001") })
        assertTrue(messages.any { it.contains("00000000-0000-0000-0000-000000000002") })
        assertTrue(messages.none { it.contains("Teleported", ignoreCase = true) })
    }

    @Test
    fun `jobs status shows queued mutation and reset entries`() {
        val regionService = mock(RegionService::class.java)
        val guiManager = mock(GuiManager::class.java)
        val plotResetService = mock(PlotResetService::class.java)
        val plotMutationService = mock(PlotMutationService::class.java)
        val player = mock(Player::class.java)

        doReturn(false).`when`(player).isOp
        doReturn(UUID.randomUUID()).`when`(player).uniqueId
        doReturn(true).`when`(player).hasPermission(anyString())
        doReturn(listOf("mutation:plots:mut-1")).`when`(plotMutationService).activeJobDiagnostics("plots")
        doReturn(listOf("reset:plots:reset-1")).`when`(plotResetService).activeJobDiagnostics("plots")
        doReturn(listOf("recovery-required:MERGE:plots:job-1")).`when`(plotResetService).recoveryRequiredDiagnostics("plots")

        val plotCommand = PlotCommand(
            regionService = regionService,
            guiManager = guiManager,
            econService = null,
            plotResetService = plotResetService,
            plotMutationService = plotMutationService
        )

        plotCommand.execute(player, arrayOf("jobs", "status", "plots"))

        verify(player, atLeastOnce()).sendMessage(contains("mut-1"))
        verify(player, atLeastOnce()).sendMessage(contains("reset-1"))
        verify(player).sendMessage(contains("recovery-required:MERGE:plots:job-1"))
    }

    @Test
    fun `jobs inspect presents persisted recovery details without resuming work`() {
        val regionService = mock(RegionService::class.java)
        val guiManager = mock(GuiManager::class.java)
        val player = mock(Player::class.java)
        val jobStore = mock(PlotMutationJobStore::class.java)
        val record = PlotMutationJobRecord(
            id = UUID.randomUUID(),
            correlationId = CorrelationId.newId(),
            operation = "RESET",
            worldId = null,
            areaId = AreaId(UUID.randomUUID()),
            worldName = "plots",
            regionIds = listOf(RegionId(UUID.randomUUID())),
            backend = "paper-native",
            status = PlotMutationJobStatus.RECOVERY_REQUIRED,
            completedUnits = 4,
            totalUnits = 12,
            error = "simulated mutation failure",
            createdAt = Instant.now()
        )
        doReturn(false).`when`(player).isOp
        doReturn(UUID.randomUUID()).`when`(player).uniqueId
        doReturn(true).`when`(player).hasPermission(anyString())
        doReturn(record).`when`(jobStore).findById(record.id)

        PlotCommand(regionService, guiManager, econService = null, mutationJobStore = jobStore)
            .execute(player, arrayOf("jobs", "inspect", record.id.toString()))

        verify(player, atLeastOnce()).sendMessage(contains(record.correlationId.toString()))
        verify(player, atLeastOnce()).sendMessage(contains("Diagnostic only"))
        verify(jobStore).findById(record.id)
    }

    @Test
    fun `jobs inspect shows completed historical jobs without recovery guidance`() {
        val regionService = mock(RegionService::class.java)
        val guiManager = mock(GuiManager::class.java)
        val player = mock(Player::class.java)
        val jobStore = mock(PlotMutationJobStore::class.java)
        val record = PlotMutationJobRecord(
            id = UUID.randomUUID(),
            correlationId = CorrelationId.newId(),
            operation = "UNLINK",
            worldId = null,
            areaId = AreaId(UUID.randomUUID()),
            worldName = "plots",
            regionIds = listOf(RegionId(UUID.randomUUID())),
            backend = "paper-native",
            status = PlotMutationJobStatus.COMPLETED
        )
        doReturn(false).`when`(player).isOp
        doReturn(UUID.randomUUID()).`when`(player).uniqueId
        doReturn(true).`when`(player).hasPermission(anyString())
        doReturn(record).`when`(jobStore).findById(record.id)

        PlotCommand(regionService, guiManager, econService = null, mutationJobStore = jobStore)
            .execute(player, arrayOf("jobs", "inspect", record.id.toString()))

        verify(player, atLeastOnce()).sendMessage(contains("COMPLETED"))
        verify(player, atLeastOnce()).sendMessage(contains("historical job"))
        verify(player, never()).sendMessage(contains("Diagnostic only"))
    }

    @Test
    fun `merge waits for world mutation before persisting and reporting completion`() {
        val regions = mergedPair()
        val auditService = mock(AuditService::class.java)
        val world = mock(World::class.java)
        val player = mergePlayer(world)
        val mutationFuture = CompletableFuture<Unit>()
        val mutationService = object : systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationService {
            override fun applyRegionState(region: Region, reason: MutationReason) = Unit
            override fun handleRegionsMerged(regions: Collection<Region>, reason: MutationReason) = mutationFuture
        }
        val command = PlotCommand(
            regions,
            mock(GuiManager::class.java),
            econService = null,
            plotMutationService = mutationService,
            auditService = auditService
        )

        command.execute(player, arrayOf("merge"))

        assertNull(regions.listAllRegions().first().mergeGroupId)
        verify(player, never()).sendMessage(contains("Merge completed"))
        mutationFuture.complete(Unit)

        val merged = regions.listAllRegions().map { it.mergeGroupId }.distinct()
        assertEquals(1, merged.size)
        assertTrue(merged.single() != null)
        val messages = Mockito.mockingDetails(player).invocations
            .mapNotNull { it.arguments.firstOrNull() as? String }
        assertTrue(messages.any { it.contains("group") || it.contains("Gruppe") }, "User should receive the merge completion message only after world mutation")
        assertTrue(messages.none { it.contains("queued", ignoreCase = true) || it.contains("eingereiht", ignoreCase = true) })
        val auditRecords = Mockito.mockingDetails(auditService).invocations
            .mapNotNull { it.arguments.firstOrNull() as? AuditEntry }
        assertEquals(listOf(AuditStatus.ATTEMPTED, AuditStatus.ALLOWED, AuditStatus.COMPLETED), auditRecords.map { it.status })
        assertEquals(1, auditRecords.map { it.correlationId }.distinct().size)
    }

    @Test
    fun `merge authorization audit failure releases reservation before any world mutation`() {
        val ownerId = UUID.randomUUID()
        val regions = mergedPair(ownerId)
        val player = mergePlayer(mock(World::class.java), ownerId)
        val failAllowedAuditOnce = AtomicBoolean(true)
        val auditRecords = mutableListOf<AuditEntry>()
        val auditService = object : AuditService {
            override fun append(entry: AuditEntry) {
                auditRecords.add(entry)
                if (entry.status == AuditStatus.ALLOWED && failAllowedAuditOnce.compareAndSet(true, false)) {
                    throw IllegalStateException("simulated audit sink failure")
                }
            }
        }
        val mutationCalls = AtomicInteger()
        val mutationService = object : PlotMutationService {
            override fun applyRegionState(region: Region, reason: MutationReason) = Unit
            override fun handleRegionsMerged(regions: Collection<Region>, reason: MutationReason): CompletableFuture<Unit> {
                mutationCalls.incrementAndGet()
                return CompletableFuture.completedFuture(Unit)
            }
        }
        val command = PlotCommand(
            regions,
            mock(GuiManager::class.java),
            econService = null,
            plotMutationService = mutationService,
            operationLimits = RegionOperationLimitService(regions),
            auditService = auditService
        )

        command.execute(player, arrayOf("merge"))

        assertEquals(0, mutationCalls.get(), "a merge whose ALLOWED audit cannot be written must stop before changing the world")
        assertTrue(regions.listAllRegions().all { it.mergeGroupId == null })
        assertTrue(auditRecords.any { it.status == AuditStatus.FAILED })

        command.execute(player, arrayOf("merge"))

        assertEquals(1, mutationCalls.get(), "the failed preflight must release its reservation so the next request can proceed")
        assertTrue(regions.listAllRegions().all { it.mergeGroupId != null })
        assertEquals(2, auditRecords.map { it.correlationId }.distinct().size)
    }

    @Test
    fun `merge journal startup failure closes queued job and releases reservation`() {
        val ownerId = UUID.randomUUID()
        val regions = mergedPair(ownerId)
        val player = mergePlayer(mock(World::class.java), ownerId)
        val failMarkRunningOnce = AtomicBoolean(true)
        val jobs = mutableMapOf<UUID, PlotMutationJobRecord>()
        val finishedJobs = mutableListOf<Pair<UUID, PlotMutationJobStatus>>()
        val jobStore = object : PlotMutationJobStore {
            override fun create(record: PlotMutationJobRecord) {
                jobs[record.id] = record
            }

            override fun markRunning(id: UUID) {
                if (failMarkRunningOnce.compareAndSet(true, false)) {
                    throw IllegalStateException("simulated journal startup failure")
                }
                jobs[id]?.let { jobs[id] = it.copy(status = PlotMutationJobStatus.RUNNING) }
            }

            override fun updateProgress(id: UUID, completedUnits: Int, totalUnits: Int) = Unit

            override fun finish(id: UUID, status: PlotMutationJobStatus, completedUnits: Int, totalUnits: Int, error: String?) {
                finishedJobs.add(id to status)
                jobs[id]?.let { jobs[id] = it.copy(status = status, completedUnits = completedUnits, totalUnits = totalUnits, error = error) }
            }

            override fun markInterruptedAsRecoveryRequired() = 0
            override fun findById(id: UUID) = jobs[id]
            override fun findRecoveryRequired(limit: Int) = jobs.values.filter { it.status == PlotMutationJobStatus.RECOVERY_REQUIRED }.take(limit)
        }
        val mutationCalls = AtomicInteger()
        val mutationService = object : PlotMutationService {
            override fun applyRegionState(region: Region, reason: MutationReason) = Unit
            override fun handleRegionsMerged(regions: Collection<Region>, reason: MutationReason): CompletableFuture<Unit> {
                mutationCalls.incrementAndGet()
                return CompletableFuture.completedFuture(Unit)
            }
        }
        val command = PlotCommand(
            regions,
            mock(GuiManager::class.java),
            econService = null,
            plotMutationService = mutationService,
            mutationJobStore = jobStore,
            operationLimits = RegionOperationLimitService(regions),
            auditService = mock(AuditService::class.java)
        )

        command.execute(player, arrayOf("merge"))

        assertEquals(0, mutationCalls.get())
        assertEquals(1, finishedJobs.size)
        assertEquals(PlotMutationJobStatus.FAILED, finishedJobs.single().second)
        assertTrue(jobs.values.single().error?.contains("journal startup failure") == true)

        command.execute(player, arrayOf("merge"))

        assertEquals(1, mutationCalls.get(), "a journal startup failure must release the region reservation")
        assertEquals(PlotMutationJobStatus.COMPLETED, finishedJobs.last().second)
    }

    @Test
    fun `merge reservation serializes overlapping requests and completed group cannot be merged twice`() {
        repeat(8) { iteration ->
            val ownerId = UUID.randomUUID()
            val world = mock(World::class.java)
            val platformWorldId = UUID.randomUUID()
            doReturn(platformWorldId).`when`(world).uid
            doReturn("plots").`when`(world).name
            val worldAreas = InMemoryWorldAreaService()
            val homeWorld = worldAreas.createWorld("plots", platformWorldId)
            val area = worldAreas.createArea(Area(AreaId.newId(), homeWorld.id, "plots", AreaScope.WholeWorld))
            val regions = mergedPair(ownerId, area.id)
            val westPlot = regions.listAllRegions().first().copy(
                id = RegionId(UUID.randomUUID()),
                bounds = Bounds(-16, -1, 0, 255, 0, 15)
            )
            regions.createRegion(westPlot)
            val auditRecords = mutableListOf<AuditEntry>()
            val auditService = object : AuditService {
                override fun append(entry: AuditEntry) {
                    auditRecords.add(entry)
                    if (iteration == 0 && entry.status == AuditStatus.COMPLETED) {
                        throw IllegalStateException("simulated audit sink failure")
                    }
                }
            }
            val player = mergePlayer(world, ownerId)
            val mutationCalls = AtomicInteger()
            val pendingMutations = ArrayDeque<CompletableFuture<Unit>>()
            val mutationService = object : PlotMutationService {
                override fun applyRegionState(region: Region, reason: MutationReason) = Unit
                override fun handleRegionsMerged(regions: Collection<Region>, reason: MutationReason): CompletableFuture<Unit> =
                    CompletableFuture<Unit>().also {
                        mutationCalls.incrementAndGet()
                        pendingMutations.addLast(it)
                    }
            }
            val command = PlotCommand(
                regions,
                mock(GuiManager::class.java),
                econService = null,
                plotMutationService = mutationService,
                auditService = auditService,
                worldAreaService = worldAreas
            )

            assertTrue(regions.getRegionAt2D(area.id, 5, 5) != null, "test fixture must index the player's plot")
            assertEquals("plots", player.location.world.name)

            command.execute(player, arrayOf("merge", "east"))
            command.execute(player, arrayOf("merge", "east"))
            assertEquals(
                1,
                mutationCalls.get(),
                "an overlapping merge must be rejected while the first world mutation is pending; audit=${auditRecords.map { it.status }}, messages=${Mockito.mockingDetails(player).invocations.mapNotNull { it.arguments.firstOrNull() as? String }}"
            )
            assertEquals(1, pendingMutations.size)

            pendingMutations.removeFirst().complete(Unit)
            assertEquals(3, regions.listAllRegions().size, "merge must preserve every persisted region")
            assertEquals(2, regions.listAllRegions().count { it.mergeGroupId != null })
            assertTrue(regions.getRegionAt2D(area.id, 5, 5) != null, "the player's plot must remain spatially indexed")
            doReturn(Location(world, -5.0, 65.0, 5.0)).`when`(player).location
            command.execute(player, arrayOf("merge", "east"))
            assertEquals(
                2,
                mutationCalls.get(),
                "a new adjacent plot must be mergeable after completion releases the reservation; audit=${auditRecords.map { it.status }}, messages=${Mockito.mockingDetails(player).invocations.mapNotNull { it.arguments.firstOrNull() as? String }}"
            )
            assertEquals(1, pendingMutations.size)
            pendingMutations.removeFirst().complete(Unit)
            assertEquals(1, regions.listAllRegions().map { it.mergeGroupId }.distinct().size)
            assertTrue(regions.listAllRegions().all { it.mergeGroupId != null })
            command.execute(player, arrayOf("merge", "east"))
            assertEquals(2, mutationCalls.get(), "the completed merge group must not be merged again")

            val statuses = auditRecords.map { it.status }
            assertEquals(2, statuses.count { it == AuditStatus.COMPLETED })
            assertTrue(statuses.none { it == AuditStatus.FAILED || it == AuditStatus.ROLLED_BACK }, "rejected duplicate requests must not create failed execution records: $statuses")
            assertTrue(pendingMutations.isEmpty(), "all started world mutations must finish")
        }
    }

    @Test
    fun `failed merge world mutation does not persist a merge and reports recovery state`() {
        val regions = mergedPair()
        val auditService = mock(AuditService::class.java)
        val world = mock(World::class.java)
        val player = mergePlayer(world)
        val mutationCalls = AtomicInteger()
        val mutationService = object : systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationService {
            override fun applyRegionState(region: Region, reason: MutationReason) = Unit
            override fun handleRegionsMerged(regions: Collection<Region>, reason: MutationReason): CompletableFuture<Unit> {
                mutationCalls.incrementAndGet()
                return CompletableFuture.failedFuture(IllegalStateException("simulated mutation failure"))
            }
        }
        val command = PlotCommand(
            regions,
            mock(GuiManager::class.java),
            econService = null,
            plotMutationService = mutationService,
            auditService = auditService
        )

        command.execute(player, arrayOf("merge"))

        assertTrue(regions.listAllRegions().all { it.mergeGroupId == null })
        val messages = Mockito.mockingDetails(player).invocations
            .mapNotNull { it.arguments.firstOrNull() as? String }
        assertTrue(messages.any { it.contains("recovery", ignoreCase = true) })
        assertTrue(messages.none { it.contains("group") || it.contains("Gruppe") })
        val auditRecords = Mockito.mockingDetails(auditService).invocations
            .mapNotNull { it.arguments.firstOrNull() as? AuditEntry }
        assertEquals(AuditStatus.FAILED, auditRecords.last().status)
        assertEquals(1, auditRecords.map { it.correlationId }.distinct().size)

        command.execute(player, arrayOf("merge"))
        assertEquals(2, mutationCalls.get(), "a failed attempt must release its reservation so a later retry can run")
        val allAuditRecords = Mockito.mockingDetails(auditService).invocations
            .mapNotNull { it.arguments.firstOrNull() as? AuditEntry }
        assertEquals(2, allAuditRecords.map { it.correlationId }.distinct().size)
    }

    @Test
    fun `merge persistence failure restores the previous world visuals and does not report success`() {
        val ownerId = UUID.randomUUID()
        val backingStore = mergedPair(ownerId)
        val regionService = object : RegionService by backingStore {
            override fun mergeRegions(regionIds: Collection<RegionId>): systems.diath.homeclaim.core.model.MergeGroupId {
                throw IllegalStateException("simulated database failure")
            }
        }
        val auditService = mock(AuditService::class.java)
        val player = mergePlayer(mock(World::class.java), ownerId)
        var worldRestored = false
        val mutationService = object : systems.diath.homeclaim.platform.paper.plot.mutation.PlotMutationService {
            override fun applyRegionState(region: Region, reason: MutationReason) = Unit
            override fun handleRegionsMerged(regions: Collection<Region>, reason: MutationReason) = CompletableFuture.completedFuture(Unit)
            override fun restoreRegionVisualStates(regions: Collection<Region>) = CompletableFuture.completedFuture(Unit).also { worldRestored = true }
        }
        val command = PlotCommand(
            regionService,
            mock(GuiManager::class.java),
            econService = null,
            plotMutationService = mutationService,
            operationLimits = RegionOperationLimitService(regionService),
            auditService = auditService
        )

        command.execute(player, arrayOf("merge"))

        assertTrue(worldRestored)
        assertTrue(backingStore.listAllRegions().all { it.mergeGroupId == null })
        val messages = Mockito.mockingDetails(player).invocations
            .mapNotNull { it.arguments.firstOrNull() as? String }
        assertTrue(messages.any { it.contains("failed", ignoreCase = true) || it.contains("fehlgeschlagen", ignoreCase = true) })
        assertTrue(messages.none { it.contains("group") || it.contains("Gruppe") })
        val auditRecords = Mockito.mockingDetails(auditService).invocations
            .mapNotNull { it.arguments.firstOrNull() as? AuditEntry }
        assertEquals(AuditStatus.ROLLED_BACK, auditRecords.last().status)
        assertEquals(1, auditRecords.map { it.correlationId }.distinct().size)
    }

    @Test
    fun `failed unlink mutation restores persisted merge state and reports rollback`() {
        val regionService = mergedPair()
        val originalRegions = regionService.listAllRegions()
        val mergeGroupId = regionService.mergeRegions(originalRegions.map { it.id })
        val auditService = mock(AuditService::class.java)
        val player = mergePlayer(mock(World::class.java))
        val mutationFuture = CompletableFuture<Unit>()
        var visualCompensationRan = false
        val mutationService = object : PlotMutationService {
            override fun applyRegionState(region: Region, reason: MutationReason) = Unit

            override fun handleRegionsUnlinked(
                regions: Collection<Region>,
                createRoads: Boolean,
                reason: MutationReason
            ) = mutationFuture

            override fun handleRegionsMerged(regions: Collection<Region>, reason: MutationReason): CompletableFuture<Unit> {
                visualCompensationRan = true
                return CompletableFuture.completedFuture(Unit)
            }
        }
        val command = PlotCommand(
            regionService,
            mock(GuiManager::class.java),
            econService = null,
            plotMutationService = mutationService,
            auditService = auditService
        )

        command.execute(player, arrayOf("unlink"))

        assertTrue(regionService.listAllRegions().all { it.mergeGroupId == null })
        val preCompletionMessages = Mockito.mockingDetails(player).invocations
            .mapNotNull { it.arguments.firstOrNull() as? String }
        assertTrue(preCompletionMessages.none { it.contains("unlink", ignoreCase = true) && it.contains("success", ignoreCase = true) })

        mutationFuture.completeExceptionally(IllegalStateException("simulated corridor failure"))

        assertTrue(visualCompensationRan)
        assertTrue(regionService.listAllRegions().all { it.mergeGroupId == mergeGroupId })
        val messages = Mockito.mockingDetails(player).invocations
            .mapNotNull { it.arguments.firstOrNull() as? String }
        assertTrue(messages.any { it.contains("failed", ignoreCase = true) || it.contains("fehlgeschlagen", ignoreCase = true) })
        assertTrue(messages.none { it.contains("Unlinked", ignoreCase = true) || it.contains("entknüpft", ignoreCase = true) })
        val auditRecords = Mockito.mockingDetails(auditService).invocations
            .mapNotNull { it.arguments.firstOrNull() as? AuditEntry }
        assertEquals(AuditStatus.ROLLED_BACK, auditRecords.last().status)
        assertEquals(1, auditRecords.map { it.correlationId }.distinct().size)
    }

    @Test
    fun `partial reset failure and cancellation require recovery after restart`() {
        assertEquals(
            PlotMutationJobStatus.RECOVERY_REQUIRED,
            PlotResetCompletion(PlotResetCompletionStatus.FAILED, 2, partialMutation = true).persistentJobStatus()
        )
        assertEquals(
            PlotMutationJobStatus.RECOVERY_REQUIRED,
            PlotResetCompletion(PlotResetCompletionStatus.CANCELLED, 2, partialMutation = true).persistentJobStatus()
        )
        assertEquals(
            PlotMutationJobStatus.FAILED,
            PlotResetCompletion(PlotResetCompletionStatus.FAILED, 0, partialMutation = false).persistentJobStatus()
        )
        assertEquals(
            PlotMutationJobStatus.CANCELLED,
            PlotResetCompletion(PlotResetCompletionStatus.CANCELLED, 0, partialMutation = false).persistentJobStatus()
        )
        assertEquals(
            PlotMutationJobStatus.COMPLETED,
            PlotResetCompletion(PlotResetCompletionStatus.COMPLETED, 10, partialMutation = false).persistentJobStatus()
        )
    }

    private fun mergedPair(
        ownerId: UUID = UUID.randomUUID(),
        areaId: AreaId = AreaId.legacyDefault(WorldId.fromLegacyName("plots"))
    ): InMemoryRegionStore {
        val store = InMemoryRegionStore()
        val anchorRegionId = RegionId(UUID.randomUUID())
        val neighborId = RegionId(UUID.randomUUID())
        val base = Region(
            id = anchorRegionId,
            world = "plots",
            areaId = areaId,
            shape = RegionShape.PLOT_GRID,
            bounds = Bounds(0, 15, 0, 255, 0, 15),
            owner = ownerId
        )
        store.createRegion(base)
        store.createRegion(base.copy(id = neighborId, bounds = Bounds(16, 31, 0, 255, 0, 15)))
        return store
    }

    private fun mergePlayer(world: World, playerId: UUID = UUID.randomUUID()): Player {
        val player = mock(Player::class.java)
        doReturn(false).`when`(player).isOp
        doReturn(true).`when`(player).isOnline
        doReturn(playerId).`when`(player).uniqueId
        doReturn(true).`when`(player).hasPermission(anyString())
        doReturn("plots").`when`(world).name
        doReturn(Location(world, 5.0, 65.0, 5.0)).`when`(player).location
        return player
    }
}
