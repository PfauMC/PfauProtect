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
        installGlobalConfiguration()

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
            TagLoader.buildUpdatedLookups(layers.getAccessForLoading(RegistryLayer.WORLD), pendingTags)
        val worldgen = RegistryDataLoader
            .load(resources, staticLookups, RegistryDataLoader.WORLD_REGISTRIES, Util.backgroundExecutor())
            .join()
        layers = layers.replaceFrom(RegistryLayer.WORLD, worldgen)
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

    // Block behaviour asks the global configuration whether a block may be destroyed at all, and the
    // piston push reaction is one of the answers behind that. Only a running server ever loads one,
    // so the defaults stand in for it here.
    private fun installGlobalConfiguration() {
        val type = Class.forName("io.papermc.paper.configuration.GlobalConfiguration")
        val instance = type.getDeclaredField("instance").apply { isAccessible = true }
        if (instance.get(null) != null) return
        val config = type.getDeclaredConstructor().newInstance()
        val unsupported = Class.forName("io.papermc.paper.configuration.GlobalConfiguration\$UnsupportedSettings")
            .getDeclaredConstructor(type)
            .apply { isAccessible = true }
            .newInstance(config)
        type.getField("unsupportedSettings").set(config, unsupported)
        instance.set(null, config)
    }
}
