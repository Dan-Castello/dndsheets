package net.hawthorn.dndsheets.species;

import io.github.edwinmindcraft.origins.api.capabilities.IOriginContainer;
import io.github.edwinmindcraft.origins.api.origin.OriginLayer;
import io.github.edwinmindcraft.origins.api.registry.OriginsDynamicRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;

/**
 * <p>Único punto de contacto con la API de Origins: leer qué origen de una capa eligió el jugador, una
 * sola vez, a pedido explícito (ver {@code RaceCommand}) — nunca por tick, nunca en un evento de alta
 * frecuencia. {@link IOriginContainer#get} devuelve la misma Capability nativa de Forge que ya expone
 * Origins (verificado con javap sobre origins-forge-1.20.1-1.10.0.9-all.jar), así que esto no toca ningún
 * mixin ni clase interna de Origins.</p>
 *
 * <p>Sirve para dos capas distintas: {@code dndsheets_species:race} (propia, ver {@link RaceRegistry}) y
 * {@code origins-classes:class} (del addon de terceros Origins:Classes, cuyo layer se reemplaza entero
 * con las 12 clases del SRD — ver {@code data/origins-classes/origin_layers/class.json}).</p>
 */
public class OriginBridge {
	/** @return el path del origen elegido en esa capa, o vacío si el jugador todavía no eligió nada ahí. */
	public static Optional<String> chosenOriginId(Player player, ResourceLocation layerId) {
		ResourceKey<OriginLayer> layerKey = ResourceKey.create(OriginsDynamicRegistries.LAYERS_REGISTRY, layerId);

		Optional<IOriginContainer> container = IOriginContainer.get(player).resolve();
		if (container.isEmpty() || !container.get().hasOrigin(layerKey)) return Optional.empty();

		ResourceKey<io.github.edwinmindcraft.origins.api.origin.Origin> origin = container.get().getOrigin(layerKey);
		return origin == null ? Optional.empty() : Optional.of(origin.location().getPath());
	}

	/**
	 * <p>La dirección inversa de {@link #chosenOriginId}: le dice a Origins qué origen tiene el personaje
	 * ACTIVO en esa capa — Origins solo guarda una elección por CUENTA de jugador, no por personaje de
	 * dndsheets (ver {@code CharacterSwitchListener}), así que sin esto el segundo personaje de alguien
	 * heredaba en silencio la elección del primero. Solo se llama con un id que ya se aplicó una vez con
	 * éxito (viene de {@code appliedRaceId}/{@code appliedBackgroundId}/{@code appliedPresetId} en la
	 * hoja), así que si Origins lo rechaza igual es un cambio externo (el DM borró ese origen del pack) y
	 * no un id inventado por este código.</p>
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
					"dndsheets_species: no pude fijar {} en la capa {} para {}: {}",
					originNamespaceId, layerId, player.getGameProfile().getName(), e.toString());
			}
		});
	}
}
