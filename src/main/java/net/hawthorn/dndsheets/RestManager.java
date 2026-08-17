package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.network.RestVoteCloseMessage;
import net.hawthorn.dndsheets.network.RestVoteOpenMessage;
import net.hawthorn.dndsheets.network.ScreenActionMessage;
import net.hawthorn.dndsheets.network.SheetClientMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * <p>Descansar es una votación, no un botón unilateral: usar el Kit de Descanso
 * ({@code {dndsheets:{restKit:true}}}) le pide al usuario que elija corto/largo, y esa propuesta se
 * manda a todos los jugadores conectados. Solo si TODOS aceptan se aplica el descanso a todo el mundo;
 * si alguien rechaza, se cancela. Solo puede haber una votación pendiente a la vez.</p>
 */
@Mod.EventBusSubscriber
public class RestManager {
	public enum RestType {
		SHORT("corto"), LONG("largo");
		public final String label;
		RestType(String label) { this.label = label; }
	}

	private static RestType pendingType = null;
	private static String pendingProposerName = null;
	private static final Set<java.util.UUID> pendingVoters = new HashSet<>();
	private static final Set<java.util.UUID> accepted = new HashSet<>();
	//Dónde se usó el Kit de Descanso: la votación (y el descanso en sí) se acota a quien esté cerca de
	//verdad en ese momento — antes se le preguntaba, y se le aplicaba el descanso, a CUALQUIERA conectado
	//al servidor, sin importar si estaba a un bloque o en otro continente del mapa.
	private static Vec3 pendingOrigin = null;

	//Sube en cada propuesta nueva; el timeout y el listener de desconexión lo capturan al programarse y
	//solo actúan si sigue siendo la MISMA propuesta cuando les toca correr — sin esto, cancelar/resolver
	//una propuesta y que otra arranque enseguida podía hacer que el timeout de la vieja cancelara la nueva.
	private static int proposalToken = 0;
	private static final int VOTE_TIMEOUT_TICKS = 3600; //3 minutos reales, mismo patrón de queueServerWork que ya usa BarbarianRageManager.

	//Se activa desde AbilityItemDispatcher (clic derecho con ítem/bloque/entidad, funciona igual en los
	//tres casos porque un ítem de bloque como el reloj mirando a una pared dispara RightClickBlock, no
	//RightClickItem) en vez de suscribirse a los 3 eventos de interacción por separado — ver
	//AUDIT_TECHNICAL.md M-EVT-1.
	static void tryOpenRestChoice(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		//Un descanso corto/largo resetea PG/espacios de golpe — en mitad de un combate por turnos eso es un
		//reset no deseado (curar gratis a mitad de pelea), no una decisión legítima de mesa. Se bloquea acá,
		//antes de abrir siquiera el selector corto/largo, en vez de solo en propose() para no hacerle elegir
		//un tipo de descanso que de todos modos se va a rechazar.
		if (TurnManager.isActive()) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.rest.blocked_in_combat").withStyle(ChatFormatting.RED));
			return;
		}
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new ScreenActionMessage(ScreenActionMessage.Action.REST_CHOICE_OPEN));
	}

	//Reloj en vez de un ítem de bloque (como la fogata): un ítem de bloque dispara RightClickBlock en vez
	//de RightClickItem en cuanto miras a algo colocable, lo que complicaba detectar el clic de forma fiable.
	public static ItemStack buildRestKitStack() {
		return AbilityItem.build(ItemLook.REST_KIT, "restKit", Component.translatable("chat.dndsheets.rest.item_name"),
			Component.translatable("chat.dndsheets.rest.lore_use").withStyle(ChatFormatting.GRAY),
			Component.translatable("chat.dndsheets.rest.lore_requires_all").withStyle(ChatFormatting.DARK_GRAY));
	}

	/**
	 * <p>Llamado al recibir un {@code RestProposeMessage}: el usuario del kit eligió corto o largo.
	 * Se manda la propuesta a todos los jugadores conectados y se cuenta al proponente como un sí.</p>
	 */
	public static void propose(ServerPlayer proposer, RestType type) {
		MinecraftServer server = proposer.getServer();
		if (server == null) return;

		//Guardado de verdad, no solo cosmético: tryOpenRestChoice ya corta esto antes de que se pueda elegir
		//corto/largo, pero un cliente que se saltara ese paso (o el modo turnos arrancando justo después de
		//abrir el selector) no debería poder colar la propuesta igual.
		if (TurnManager.isActive()) {
			proposer.sendSystemMessage(Component.translatable("chat.dndsheets.rest.blocked_in_combat").withStyle(ChatFormatting.RED));
			return;
		}

		if (pendingType != null) {
			proposer.sendSystemMessage(Component.translatable("chat.dndsheets.rest.vote_in_progress").withStyle(ChatFormatting.RED));
			return;
		}

		JsonObject proposerSheet = SheetLoader.getServerSheet(proposer.getStringUUID());
		String proposerName = SheetLoader.characterNameOf(proposerSheet, proposer);

		pendingType = type;
		pendingProposerName = proposerName;
		pendingOrigin = proposer.position();
		pendingVoters.clear();
		accepted.clear();
		int token = ++proposalToken;

		List<ServerPlayer> nearby = nearbyPlayers(server, pendingOrigin);
		for (ServerPlayer player : nearby) pendingVoters.add(player.getUUID());

		for (ServerPlayer player : nearby) {
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new RestVoteOpenMessage(proposerName, type.label));
		}
		notifyVoters(server, Component.translatable("chat.dndsheets.rest.proposed", proposerName, type.label).withStyle(ChatFormatting.AQUA));

		//Sin esto, un jugador que nunca responde (sin desconectarse, solo ignora el prompt) bloqueaba
		//los descansos de todo el servidor para siempre — no solo el caso de desconexión, ver onPlayerLogout.
		DndsheetsMod.queueServerWork(VOTE_TIMEOUT_TICKS, () -> {
			if (pendingType == null || token != proposalToken) return;
			notifyVoters(server, Component.translatable("chat.dndsheets.rest.timed_out").withStyle(ChatFormatting.RED));
			clear(server);
		});

		registerVote(proposer, true); //El proponente ya vota que sí, por proponerlo.
	}

	//Mismo radio que ya usa el modo turnos para "quién participa" (TurnManager.DEFAULT_RADIUS) —
	//consistente con el resto del mod para decidir a quién le concierne una acción puntual como esta.
	private static List<ServerPlayer> nearbyPlayers(MinecraftServer server, Vec3 origin) {
		double radiusSq = TurnManager.DEFAULT_RADIUS * TurnManager.DEFAULT_RADIUS;
		return server.getPlayerList().getPlayers().stream()
			.filter(p -> p.position().distanceToSqr(origin) <= radiusSq)
			.toList();
	}

	//Manda un mensaje solo a quien de verdad forma parte de ESTA votación (pendingVoters), no a todo el
	//servidor — reusado por propose/registerVote/resolveRest para las 4 líneas de chat del ciclo de vida
	//de una propuesta (propuesta, rechazo, expiración, descanso completo).
	private static void notifyVoters(MinecraftServer server, Component message) {
		for (UUID uuid : pendingVoters) {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player != null) player.sendSystemMessage(message);
		}
	}

	//Sin esto, un jugador que se desconecta (crash, cierre) antes de votar dejaba pendingVoters con un
	//UUID que jamás iba a aceptar: accepted nunca podía igualarlo, y pendingType != null bloqueaba
	//cualquier propuesta nueva — nadie en el servidor podía volver a descansar hasta reiniciar.
	@SubscribeEvent
	public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		if (pendingType == null || !(event.getEntity() instanceof ServerPlayer player)) return;
		UUID uuid = player.getUUID();
		if (!pendingVoters.remove(uuid)) return; //No formaba parte de esta votación.

		accepted.remove(uuid);
		if (pendingVoters.isEmpty()) { clear(player.getServer()); return; } //Nadie queda a quien pedirle el descanso.
		if (accepted.containsAll(pendingVoters)) resolveRest(player.getServer());
	}

	//Simétrico a onPlayerLogout: sin esto, alguien que se conecta mientras hay una votación pendiente
	//nunca era preguntado, pero resolveRest le aplicaba el descanso igual en cuanto el resto aceptaba —
	//se le suma a la votación, como a cualquier otro, y se le manda el mismo prompt que ya recibieron los
	//demás. Solo si está cerca de dónde se propuso: alguien que se conecta lejos de esa zona no debería
	//verse arrastrado a una votación de un grupo con el que ni siquiera está jugando.
	@SubscribeEvent
	public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (pendingType == null || !(event.getEntity() instanceof ServerPlayer player)) return;
		if (pendingOrigin != null && player.position().distanceToSqr(pendingOrigin) > TurnManager.DEFAULT_RADIUS * TurnManager.DEFAULT_RADIUS) return;
		pendingVoters.add(player.getUUID());
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new RestVoteOpenMessage(pendingProposerName, pendingType.label));
	}

	/**
	 * <p>Llamado al recibir un {@code RestVoteResponseMessage}.</p>
	 */
	public static void registerVote(ServerPlayer player, boolean accept) {
		if (pendingType == null || !pendingVoters.contains(player.getUUID())) return;

		if (!accept) {
			MinecraftServer server = player.getServer();
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			String name = SheetLoader.characterNameOf(sheet, player);
			if (server != null) {
				notifyVoters(server, Component.translatable("chat.dndsheets.rest.rejected", name).withStyle(ChatFormatting.RED));
			}
			clear(server);
			return;
		}

		accepted.add(player.getUUID());
		if (accepted.containsAll(pendingVoters)) {
			resolveRest(player.getServer());
		}
	}

	private static void resolveRest(MinecraftServer server) {
		if (server == null) return;
		RestType type = pendingType;
		//Solo a quien de verdad votó (pendingVoters), no a todo el servidor: antes esto le reseteaba PG/
		//espacios de conjuro a CUALQUIERA conectado en cuanto el grupo que sí estaba descansando terminaba
		//de aceptar, así estuviera a medio mapa de distancia sin haber sido ni siquiera preguntado.
		for (UUID uuid : pendingVoters) {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player != null) applyRest(player, type);
		}
		notifyVoters(server, Component.translatable("chat.dndsheets.rest.completed", type.label).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
		clear(server);
	}

	private static void applyRest(ServerPlayer player, RestType type) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		SheetLoader.validateSheet(sheet);

		if (type == RestType.LONG) {
			player.setHealth(player.getMaxHealth());
			int max = sheet.get("spellSlotsMax").getAsInt();
			SpellSlots.restoreAll(sheet);
			WizardArcaneRecoveryManager.resetOnLongRest(player);
		} else {
			float missing = player.getMaxHealth() - player.getHealth();
			player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + missing / 2f));
			WizardArcaneRecoveryManager.onShortRest(player, sheet);
			WarlockPactMagicManager.onShortRest(player, sheet);
		}
		FighterSecondWindManager.resetOnRest(player); //5e lo recupera con cualquiera de los dos descansos, no solo el largo.
		ClericTurnUndeadManager.resetOnRest(player);  //Canalizar Divinidad, igual: se recupera con el descanso corto.

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SheetClientMessage(sheet.toString().getBytes()));
	}

	//"server" puede ser null (p.ej. un jugador desconectándose a mitad de shutdown); en ese caso simplemente
	//no hay a quién avisar. Antes de esto, RestVoteScreen.close() existía pero nadie lo llamaba nunca: quien
	//no había votado todavía se quedaba con una pantalla de "Aceptar/Rechazar" muerta para una votación ya
	//resuelta/expirada/cancelada, sin ningún indicio de que ya no servía de nada.
	private static void clear(MinecraftServer server) {
		if (server != null) {
			for (UUID uuid : pendingVoters) {
				ServerPlayer voter = server.getPlayerList().getPlayer(uuid);
				if (voter != null) DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> voter), new RestVoteCloseMessage());
			}
		}
		pendingType = null;
		pendingProposerName = null;
		pendingOrigin = null;
		pendingVoters.clear();
		accepted.clear();
	}
}
