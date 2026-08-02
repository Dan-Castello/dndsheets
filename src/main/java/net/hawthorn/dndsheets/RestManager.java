package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.network.RestChoiceOpenMessage;
import net.hawthorn.dndsheets.network.RestVoteCloseMessage;
import net.hawthorn.dndsheets.network.RestVoteOpenMessage;
import net.hawthorn.dndsheets.network.SheetClientMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
	private static final Set<java.util.UUID> pendingVoters = new HashSet<>();
	private static final Set<java.util.UUID> accepted = new HashSet<>();

	//Sube en cada propuesta nueva; el timeout y el listener de desconexión lo capturan al programarse y
	//solo actúan si sigue siendo la MISMA propuesta cuando les toca correr — sin esto, cancelar/resolver
	//una propuesta y que otra arranque enseguida podía hacer que el timeout de la vieja cancelara la nueva.
	private static int proposalToken = 0;
	private static final int VOTE_TIMEOUT_TICKS = 3600; //3 minutos reales, mismo patrón de queueServerWork que ya usa BarbarianRageManager.

	//Se engancha a los tres eventos de "clic derecho" de Minecraft (ítem al aire, bloque, entidad),
	//igual que QuickSpellManager: cuál de los tres dispara depende de qué haya delante del jugador, y el
	//kit debe funcionar igual en los tres casos (ítem/bloque en particular, ya que un ítem de bloque como
	//el reloj mirando a una pared dispararía RightClickBlock, no RightClickItem).
	@SubscribeEvent
	public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
		tryOpenRestChoice(event, event.getItemStack());
	}

	@SubscribeEvent
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		tryOpenRestChoice(event, event.getItemStack());
	}

	@SubscribeEvent
	public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		tryOpenRestChoice(event, event.getItemStack());
	}

	private static void tryOpenRestChoice(PlayerInteractEvent event, ItemStack stack) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains("dndsheets") || !tag.getCompound("dndsheets").getBoolean("restKit")) return;

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
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new RestChoiceOpenMessage());
	}

	//Reloj en vez de un ítem de bloque (como la fogata): un ítem de bloque dispara RightClickBlock en vez
	//de RightClickItem en cuanto miras a algo colocable, lo que complicaba detectar el clic de forma fiable.
	public static ItemStack buildRestKitStack() {
		ItemStack stack = new ItemStack(Items.CLOCK);
		CompoundTag dndTag = new CompoundTag();
		dndTag.putBoolean("restKit", true);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		stack.setHoverName(Component.translatable("chat.dndsheets.rest.item_name"));

		net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.translatable("chat.dndsheets.rest.lore_use").withStyle(ChatFormatting.GRAY))));
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.translatable("chat.dndsheets.rest.lore_requires_all").withStyle(ChatFormatting.DARK_GRAY))));
		stack.getOrCreateTagElement("display").put("Lore", lore);

		return stack;
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
		pendingVoters.clear();
		accepted.clear();
		int token = ++proposalToken;

		List<ServerPlayer> online = server.getPlayerList().getPlayers();
		for (ServerPlayer player : online) pendingVoters.add(player.getUUID());

		for (ServerPlayer player : online) {
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new RestVoteOpenMessage(proposerName, type.label));
		}
		server.getPlayerList().broadcastSystemMessage(
			Component.translatable("chat.dndsheets.rest.proposed", proposerName, type.label).withStyle(ChatFormatting.AQUA), false
		);

		//Sin esto, un jugador que nunca responde (sin desconectarse, solo ignora el prompt) bloqueaba
		//los descansos de todo el servidor para siempre — no solo el caso de desconexión, ver onPlayerLogout.
		DndsheetsMod.queueServerWork(VOTE_TIMEOUT_TICKS, () -> {
			if (pendingType == null || token != proposalToken) return;
			server.getPlayerList().broadcastSystemMessage(
				Component.translatable("chat.dndsheets.rest.timed_out").withStyle(ChatFormatting.RED), false
			);
			clear(server);
		});

		registerVote(proposer, true); //El proponente ya vota que sí, por proponerlo.
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
				server.getPlayerList().broadcastSystemMessage(Component.translatable("chat.dndsheets.rest.rejected", name).withStyle(ChatFormatting.RED), false);
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
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			applyRest(player, type);
		}
		server.getPlayerList().broadcastSystemMessage(
			Component.translatable("chat.dndsheets.rest.completed", type.label).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), false
		);
		clear(server);
	}

	private static void applyRest(ServerPlayer player, RestType type) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		SheetLoader.validateSheet(sheet);

		if (type == RestType.LONG) {
			player.setHealth(player.getMaxHealth());
			int max = sheet.get("spellSlotsMax").getAsInt();
			sheet.addProperty("spellSlotsCurrent", max);
			WizardArcaneRecoveryManager.resetOnLongRest(player);
		} else {
			float missing = player.getMaxHealth() - player.getHealth();
			player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + missing / 2f));
			WizardArcaneRecoveryManager.onShortRest(player, sheet);
			WarlockPactMagicManager.onShortRest(player, sheet);
		}
		FighterSecondWindManager.resetOnRest(player); //5e lo recupera con cualquiera de los dos descansos, no solo el largo.

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
		pendingVoters.clear();
		accepted.clear();
	}
}
