package net.hawthorn.dndsheets;

import net.hawthorn.dndsheets.network.DungeonJigsawConfigureOpenMessage;
import net.hawthorn.dndsheets.network.DungeonPieceAddOpenMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <p>Clic derecho con la Vara de DM ({@link MonsterRegistry#isDmTool}) sobre un bloque de estructura ya
 * nombrado abre "Añadir pieza" prellenado con ese id, en vez de obligar al DM a retipear a mano el mismo
 * id que ya escribió una vez al guardar la estructura (ver {@link net.hawthorn.dndsheets.client.gui.DungeonPieceAddScreen}).
 * Lo mismo sobre un jigsaw block abre un formulario corto que le escribe Name/Target/Pool directo (ver
 * {@link net.hawthorn.dndsheets.client.gui.DungeonJigsawConfigureScreen}), sin pasar por la GUI vanilla
 * del jigsaw ni tipear a mano los 3 strings exactos con nuestro namespace. Agachado + clic derecho copia
 * la configuración de un jigsaw a un portapapeles por DM, que prellena (no pega solo, sigue pidiendo
 * confirmar) el formulario de cualquier otro jigsaw que toque después — para dar varias salidas al mismo
 * pool sin repetir el nombre a mano en cada una.</p>
 */
@Mod.EventBusSubscriber
public class DungeonToolManager {
	private record JigsawClipboard(String pool, boolean isStart) {}

	//Por DM, no global: dos DMs trabajando en la misma partida no deberían pisarse el portapapeles.
	private static final Map<UUID, JigsawClipboard> jigsawClipboard = new HashMap<>();

	@SubscribeEvent
	public static void onCaptureFromStructureBlock(PlayerInteractEvent.RightClickBlock event) {
		if (!MonsterRegistry.isDmTool(event.getItemStack())) return;
		if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof StructureBlockEntity structureBlock)) return;

		cancelBothHandsRetry(event);
		if (event.getLevel().isClientSide()) return;

		Player dm = event.getEntity();
		if (!dm.hasPermissions(2)) return;
		if (!(dm instanceof ServerPlayer serverDm)) return;

		String structureId = structureBlock.getStructureName();
		if (structureId == null || structureId.isBlank()) {
			serverDm.sendSystemMessage(Component.literal("Este bloque de estructura todavía no tiene nombre — ponle uno y guárdalo primero.").withStyle(ChatFormatting.GRAY));
			return;
		}

		ResourceLocation parsed = ResourceLocation.tryParse(structureId);
		String suggestedId = parsed == null ? "" : suggestIdFrom(parsed.getPath());

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> serverDm), new DungeonPieceAddOpenMessage(structureId, suggestedId));
	}

	//"rooms/entrance" -> "entrance": solo el último segmento de la ruta, así "id" no arranca ya lleno de
	//barras que igual habría que editar a mano.
	private static String suggestIdFrom(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? path : path.substring(slash + 1);
	}

	@SubscribeEvent
	public static void onConfigureJigsaw(PlayerInteractEvent.RightClickBlock event) {
		if (!MonsterRegistry.isDmTool(event.getItemStack())) return;
		if (!(event.getLevel().getBlockEntity(event.getPos()) instanceof JigsawBlockEntity jigsaw)) return;

		cancelBothHandsRetry(event);
		if (event.getLevel().isClientSide()) return;

		Player dm = event.getEntity();
		if (!dm.hasPermissions(2)) return;
		if (!(dm instanceof ServerPlayer serverDm)) return;

		//Solo cuenta como "configurado" si es DE NUESTRO namespace — un jigsaw recién colocado trae
		//"minecraft:empty" en Name/Pool por defecto (ver JigsawBlockEntity), que no es un pool real elegido.
		ResourceLocation currentPoolLocation = jigsaw.getPool().location();
		boolean isConfigured = DungeonManager.POOL_NAMESPACE.equals(currentPoolLocation.getNamespace());
		boolean currentIsStart = jigsaw.getName().equals(new ResourceLocation(DungeonManager.START_JIGSAW_NAME));

		//Agachado: copia este jigsaw al portapapeles en vez de abrir el formulario — separado de un clic
		//normal para que copiar sea una acción explícita, nunca un efecto secundario de configurar.
		if (dm.isShiftKeyDown()) {
			if (!isConfigured) {
				serverDm.sendSystemMessage(Component.literal("Este jigsaw todavía no está configurado — nada que copiar.").withStyle(ChatFormatting.GRAY));
				return;
			}
			jigsawClipboard.put(dm.getUUID(), new JigsawClipboard(currentPoolLocation.getPath(), currentIsStart));
			serverDm.sendSystemMessage(Component.literal("Copiado: pool \"" + currentPoolLocation.getPath() + "\"" + (currentIsStart ? " (inicio)." : ".")).withStyle(ChatFormatting.GRAY));
			return;
		}

		//El portapapeles (si hay uno) prellena el formulario en vez de lo que ya tuviera ESTE jigsaw — dar
		//varias salidas al mismo pool es copiar una vez y solo confirmar en las demás, sin retipear. Sigue
		//pidiendo confirmar (nunca escribe solo): un clic normal no debe sobreescribir en silencio.
		JigsawClipboard copied = jigsawClipboard.get(dm.getUUID());
		String prefillPool = copied != null ? copied.pool() : (isConfigured ? currentPoolLocation.getPath() : "");
		boolean prefillIsStart = copied != null ? copied.isStart() : currentIsStart;

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> serverDm), new DungeonJigsawConfigureOpenMessage(event.getPos(), prefillPool, prefillIsStart));
	}

	//Cancela en AMBOS lados (cliente Y servidor), no solo en el servidor: el cliente decide en su propia
	//predicción local si reintentar la interacción con la otra mano cuando la mano usada "no consume"
	//nada (comportamiento vanilla de mano principal -> secundaria). Sin cancelar también en el cliente
	//con un resultado que SÍ consume, el cliente mandaba un segundo paquete con la otra mano y el servidor
	//terminaba procesando la acción dos veces — se veía como un mensaje de chat duplicado.
	private static void cancelBothHandsRetry(PlayerInteractEvent.RightClickBlock event) {
		event.setCanceled(true);
		event.setCancellationResult(InteractionResult.SUCCESS);
	}
}
