package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.world.inventory.CharacterSheetMenu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;

import java.util.function.Supplier;

/**
 * <p>Cliente -&gt; servidor: abre la ficha de personaje, o la cierra si ya estaba abierta (la tecla H y
 * el botón "Ficha" de los dos editores de tiradas alternan con el mismo mensaje).</p>
 *
 * <p>Ya no lleva los campos {@code type} y {@code pressedms} que generaba MCreator: los tres llamadores
 * mandaban {@code (0, 0)} y el handler solo tenía rama para {@code type == 0}. Quitarlos cambia lo que
 * viaja por el cable, así que sube {@code PROTOCOL_VERSION} — ver la invariante 1.</p>
 */
public class CharacterSheetOpenMessage {

	public CharacterSheetOpenMessage() {
	}

	public CharacterSheetOpenMessage(FriendlyByteBuf buffer) {
	}

	public static void buffer(CharacterSheetOpenMessage message, FriendlyByteBuf buffer) {
	}

	public static void handler(CharacterSheetOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> pressAction(context.getSender()));
	}

	/**
	 * <p>Público y con {@code Player} (no {@code ServerPlayer}) porque el cliente también lo llama con su
	 * propio jugador nada más mandar el paquete (ver {@code DndsheetsModKeyMappings}): así el cierre se ve
	 * al instante en vez de esperar al viaje de ida y vuelta. En el cliente solo entra por la rama de
	 * cerrar; abrir es cosa del servidor, que es quien tiene el {@code ServerPlayer}.</p>
	 */
	public static void pressAction(Player entity) {
		if (entity == null) return;

		if (entity.containerMenu instanceof CharacterSheetMenu) {
			entity.closeContainer();
		} else if (entity instanceof ServerPlayer player) {
			//Sin BlockPos: la pantalla no depende de dónde se abrió. De paso se va el guard de
			//"hasChunkAt" que traía la plantilla — protegía de generar chunk en coordenadas arbitrarias, y
			//aquí ya no hay coordenada ninguna que pasar.
			NetworkHooks.openScreen(player, new SimpleMenuProvider(
				(id, inventory, viewer) -> new CharacterSheetMenu(id, inventory, null), Component.literal("CharacterSheet")));
		}
	}
}
