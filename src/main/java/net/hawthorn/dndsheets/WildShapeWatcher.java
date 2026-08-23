package net.hawthorn.dndsheets;

import net.hawthorn.dndsheets.network.WildShapeMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Quién ve a quién transformado. La forma vive en la hoja del druida, pero <b>dibujarlo</b> es cosa de
 * los clientes de los demás, y la hoja de un jugador no se le manda a nadie más — así que la forma tiene
 * que viajar aparte.</p>
 *
 * <p>Se difunde a TODOS y no solo a quien está cerca: la lista de jugadores conectados de una mesa de
 * D&amp;D cabe en una mano, y "cerca" habría que recalcularlo cada vez que alguien camina, lo que es
 * mucho más caro que mandar un paquete de treinta bytes cuando alguien se transforma.</p>
 *
 * <p>Y se reenvía en cada entrada al mundo por el mismo motivo que las condiciones
 * ({@code DeathSaveManager.resendState}): quien acaba de conectarse no vio el paquete original, y sin
 * esto vería al oso como un jugador normal hasta que se destransformara.</p>
 */
@Mod.EventBusSubscriber
public class WildShapeWatcher {

	private WildShapeWatcher() {
	}

	/** Cuenta a todo el mundo en qué se ha convertido este jugador. Id vacío = ha vuelto a su forma. */
	public static void broadcast(ServerPlayer player, String monsterId) {
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.ALL.noArg(),
			new WildShapeMessage(WildShapeMessage.Kind.SHAPE, player.getUUID(), baseEntityIdOf(monsterId)));
	}

	/**
	 * <p>El registro de monstruos solo vive de verdad en el servidor: un cliente que sea un proceso aparte
	 * (cualquier invitado por LAN que no sea quien abrió el mundo) lo ve siempre vacío. Por eso el SHAPE que
	 * viaja al resto no lleva el id del monstruo — llevaría al renderer de vuelta a consultar ese registro
	 * vacío — sino la entidad base ya resuelta aquí, que es lo único que {@code WildShapeRenderer} necesita
	 * para elegir el modelo.</p>
	 */
	private static String baseEntityIdOf(String monsterId) {
		if (monsterId == null || monsterId.isEmpty()) return "";
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(monsterId);
		return block != null ? block.baseEntityId() : "";
	}

	/** Resuelve el bestiario EN EL SERVIDOR, que es donde de verdad vive: ver el comentario de WildShapeMessage. */
	public static void openPicker(ServerPlayer player) {
		List<String> ids = DruidWildShapeManager.beastIds();
		List<String> names = new ArrayList<>();
		List<Integer> hps = new ArrayList<>();
		List<Integer> acs = new ArrayList<>();
		for (String id : ids) {
			MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(id);
			names.add(block.name());
			hps.add(block.maxHp());
			acs.add(block.ac());
		}
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player),
			new WildShapeMessage(player.getUUID(), ids, names, hps, acs));
	}

	/**
	 * <p>Al entrar alguien al mundo se le pone al día de quién está transformado, y se recuerda su propia
	 * forma al resto. Las dos direcciones hacen falta: el que llega no vio los paquetes de antes, y los que
	 * ya estaban no vieron el suyo.</p>
	 */
	@SubscribeEvent
	public static void onJoin(EntityJoinLevelEvent event) {
		if (event.getLevel().isClientSide()) return;
		if (!(event.getEntity() instanceof ServerPlayer joined) || joined.getServer() == null) return;

		for (ServerPlayer other : joined.getServer().getPlayerList().getPlayers()) {
			String shape = DruidWildShapeManager.shapeOf(SheetLoader.getServerSheet(other.getStringUUID()));
			if (shape == null) continue;
			//Al que llega, la forma de cada uno; y si el que llega venía transformado, a todos la suya.
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> joined),
				new WildShapeMessage(WildShapeMessage.Kind.SHAPE, other.getUUID(), baseEntityIdOf(shape)));
			if (other == joined) broadcast(joined, shape);
		}
	}
}
