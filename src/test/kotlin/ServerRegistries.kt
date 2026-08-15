package io.pfaumc.pfauprotect

import net.minecraft.SharedConstants
import net.minecraft.commands.Commands
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.RegistryDataLoader
import net.minecraft.server.Bootstrap
import net.minecraft.server.MinecraftServer
import net.minecraft.server.RegistryLayer
import net.minecraft.server.ReloadableServerResources
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.server.packs.resources.MultiPackResourceManager
import net.minecraft.server.permissions.PermissionSet
import net.minecraft.tags.TagLoader
import net.minecraft.util.Util
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.level.DataPackConfig
import net.minecraft.world.level.WorldDataConfiguration

// Item prototype components are bound by a datapack load, not by Bootstrap, and without them every
// ItemStack constructor throws.
object ServerRegistries {
    val access: RegistryAccess by lazy { load() }

    private fun load(): RegistryAccess {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        Bootstrap.validate()

        val flags = FeatureFlags.VANILLA_SET
        val packs = ServerPacksSource.createVanillaTrustedRepository()
        MinecraftServer.configurePackRepository(
            packs,
            WorldDataConfiguration(
                DataPackConfig(FeatureFlags.REGISTRY.toNames(flags).map { it.path }, emptyList()),
                flags,
            ),
            true,
            false,
        )
        val resources = MultiPackResourceManager(PackType.SERVER_DATA, packs.openAllSelected())

        var layers = RegistryLayer.createRegistryAccess()
        val pendingTags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC))
        val staticLookups =
            TagLoader.buildUpdatedLookups(layers.getAccessForLoading(RegistryLayer.WORLDGEN), pendingTags)
        val worldgen = RegistryDataLoader
            .load(resources, staticLookups, RegistryDataLoader.WORLDGEN_REGISTRIES, Util.backgroundExecutor())
            .join()
        layers = layers.replaceFrom(RegistryLayer.WORLDGEN, worldgen)
        val worldgenLookups = staticLookups + worldgen.listRegistries().toList()
        val dimensions = RegistryDataLoader
            .load(resources, worldgenLookups, RegistryDataLoader.DIMENSION_REGISTRIES, Util.backgroundExecutor())
            .join()
        layers = layers.replaceFrom(RegistryLayer.DIMENSIONS, dimensions)

        ReloadableServerResources.loadResources(
            resources,
            layers,
            pendingTags,
            flags,
            Commands.CommandSelection.DEDICATED,
            PermissionSet.ALL_PERMISSIONS,
            Util.backgroundExecutor(),
            Util.backgroundExecutor(),
        ).join().updateComponentsAndStaticRegistryTags()

        return layers.compositeAccess().freeze()
    }
}
