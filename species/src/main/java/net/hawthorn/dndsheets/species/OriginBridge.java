package net.hawthorn.dndsheets.species;

import io.github.edwinmindcraft.origins.api.capabilities.IOriginContainer;
import io.github.edwinmindcraft.origins.api.origin.OriginLayer;
import io.github.edwinmindcraft.origins.api.registry.OriginsDynamicRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;

/**
 * <p>Single point of contact with the Origins API: read which origin the player chose for a layer, once,
 * on explicit request (see {@code RaceCommand}) — never per tick, never in a high-frequency event.
 * {@link IOriginContainer#get} returns the same native Forge Capability that Origins already exposes
 * (verified with javap on origins-forge-1.20.1-1.10.0.9-all.jar), so this doesn't touch any Origins mixin
 * or internal class.</p>
 *
 * <p>Used for two distinct layers: {@code dndsheets_species:race} (our own, see {@link RaceRegistry}) and
 * {@code origins-classes:class} (from the third-party addon Origins:Classes, whose layer is replaced
 * entirely with the 12 SRD classes — see {@code data/origins-classes/origin_layers/class.json}).</p>
 */
public class OriginBridge {
	/** @return the path of the origin chosen in that layer, or empty if the player hasn't chosen anything there yet. */
	public static Optional<String> chosenOriginId(Player player, ResourceLocation layerId) {
		ResourceKey<OriginLayer> layerKey = ResourceKey.create(OriginsDynamicRegistries.LAYERS_REGISTRY, layerId);

		Optional<IOriginContainer> container = IOriginContainer.get(player).resolve();
		if (container.isEmpty() || !container.get().hasOrigin(layerKey)) return Optional.empty();

		ResourceKey<io.github.edwinmindcraft.origins.api.origin.Origin> origin = container.get().getOrigin(layerKey);
		return origin == null ? Optional.empty() : Optional.of(origin.location().getPath());
	}

	/**
	 * <p>The reverse direction of {@link #chosenOriginId}: tells Origins which origin the ACTIVE character
	 * has in that layer — Origins only stores one choice per player ACCOUNT, not per dndsheets character
	 * (see {@code CharacterSwitchListener}), so without this someone's second character would silently
	 * inherit the first character's choice. Only ever called with an id that was already successfully
	 * applied once (comes from {@code appliedRaceId}/{@code appliedBackgroundId}/{@code appliedPresetId}
	 * on the sheet), so if Origins rejects it anyway that's an external change (the DM deleted that origin
	 * from the pack), not an id invented by this code.</p>
	 */
	public static void pushOriginId(Player player, ResourceLocation layerId, ResourceLocation originNamespaceId) {
		ResourceKey<OriginLayer> layerKey = ResourceKey.create(OriginsDynamicRegistries.LAYERS_REGISTRY, layerId);
		ResourceKey<io.github.edwinmindcraft.origins.api.origin.Origin> originKey =
			ResourceKey.create(OriginsDynamicRegistries.ORIGINS_REGISTRY, originNamespaceId);

		IOriginContainer.get(player).ifPresent(container -> {
			try {
				container.setOrigin(layerKey, originKey);
			} catch (RuntimeException e) {
				net.hawthorn.dndsheets.DndsheetsMod.LOGGER.warn(
					"dndsheets_species: could not set {} on layer {} for {}: {}",
					originNamespaceId, layerId, player.getGameProfile().getName(), e.toString());
			}
		});
	}
}
