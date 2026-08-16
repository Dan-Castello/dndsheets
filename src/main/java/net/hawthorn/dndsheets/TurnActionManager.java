package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>Las acciones de turno de 5e que no son atacar ni lanzar un conjuro: <b>Esquivar</b>, <b>Correr</b> y
 * <b>Desengancharse</b>.</p>
 *
 * <p>Hasta ahora un turno solo podía gastarse en pegar o en lanzar algo, así que el modo turnos era un
 * "quién pega ahora" por orden. Estas tres son las que convierten el turno en una decisión: un personaje
 * acorralado y con pocos PG casi nunca quiere atacar, quiere salir de ahí sin comerse un ataque de
 * oportunidad, o cubrirse y aguantar el asalto.</p>
 *
 * <p>Ninguna necesita reglas nuevas: las tres se enchufan a maquinaria que ya existía y que no se estaba
 * usando para nada más — el presupuesto de movimiento de {@link MovementAnchorTracker}, el registro de
 * ataques de oportunidad de {@code OpportunityAttackTracker} y la ventaja/desventaja de
 * {@link Combatant#advantageAgainst}.</p>
 *
 * <p><b>Falta Ayudar</b>, la cuarta: da ventaja al ataque de un aliado y por tanto necesita a quién
 * señalar, igual que la Inspiración Bárdica. Es un ítem de interactuar-con-entidad, no una entrada de este
 * menú, y por eso no entra aquí.</p>
 */
public class TurnActionManager {

	public enum TurnAction {
		/** Todo ataque contra ti tiene desventaja hasta tu próximo turno. */
		DODGE,
		/** Doble de movimiento este turno. */
		DASH,
		/** Alejarse no provoca ataques de oportunidad este turno. */
		DISENGAGE,
	}

	//Un solo mapa para las tres, y no tres conjuntos sueltos: caducan todas a la vez (al empezar el
	//siguiente turno de quien las usó) y se limpian desde el mismo sitio, así que separarlas solo daría
	//tres cosas que acordarse de vaciar en vez de una.
	private static final Map<Integer, EnumSet<TurnAction>> active = new HashMap<>();

	public static void use(ServerPlayer player, TurnAction action) {
		//Fuera de combate no significan nada: no hay turno que gastar, ni ataques de oportunidad, ni
		//presupuesto de movimiento que doblar. Decirlo es mejor que aceptar el clic y no hacer nada.
		if (!TurnManager.isActive()) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.action.needs_combat").withStyle(ChatFormatting.GRAY));
			return;
		}
		//Son LA acción del turno, igual que atacar: se comprueba con el mismo tryAct, así que gastarla
		//también termina el turno solo y no se puede esquivar Y atacar en el mismo asalto.
		if (!TurnManager.tryAct(player)) {
			TurnManager.notifyCantAct(player);
			return;
		}

		active.computeIfAbsent(player.getId(), id -> EnumSet.noneOf(TurnAction.class)).add(action);
		CombatFx.activate(player);
		String name = SheetLoader.characterNameOf(SheetLoader.getServerSheet(player.getStringUUID()), player);
		ChatFeedback.broadcast(player, Component.translatable(messageKeyOf(action), name).withStyle(ChatFeedback.RESOURCE));
	}

	private static String messageKeyOf(TurnAction action) {
		return switch (action) {
			case DODGE -> "chat.dndsheets.action.dodge";
			case DASH -> "chat.dndsheets.action.dash";
			case DISENGAGE -> "chat.dndsheets.action.disengage";
		};
	}

	private static boolean has(Entity entity, TurnAction action) {
		EnumSet<TurnAction> taken = entity == null ? null : active.get(entity.getId());
		return taken != null && taken.contains(action);
	}

	/** Todo ataque contra él tiene desventaja. Dura hasta que le vuelva a tocar, o sea todo el asalto. */
	public static boolean isDodging(Entity entity) {
		return has(entity, TurnAction.DODGE);
	}

	/** Doble presupuesto de movimiento este turno — ver {@link MovementAnchorTracker}. */
	public static boolean isDashing(Entity entity) {
		return has(entity, TurnAction.DASH);
	}

	/** Alejarse no provoca ataques de oportunidad — ver {@code OpportunityAttackTracker}. */
	public static boolean isDisengaged(Entity entity) {
		return has(entity, TurnAction.DISENGAGE);
	}

	/**
	 * <p>Caducan al empezar SU siguiente turno, que es literalmente lo que dice Esquivar en 5e ("hasta el
	 * comienzo de tu próximo turno"). Correr y Desengancharse solo valen durante su propio turno, así que
	 * limpiarlas aquí también es correcto y de paso no deja tres relojes distintos que cuadrar.</p>
	 */
	static void clearFor(int entityId) {
		active.remove(entityId);
	}

	/** Se vacía entero al terminar un encuentro: fuera de combate ninguna de las tres significa nada. */
	static void clearAll() {
		active.clear();
	}

	//Se activa desde AbilityItemDispatcher, igual que el resto de ítems de capacidad.
	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		DndsheetsMod.PACKET_HANDLER.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
			new net.hawthorn.dndsheets.network.ScreenActionMessage(net.hawthorn.dndsheets.network.ScreenActionMessage.Action.TURN_ACTION_OPEN));
	}

	public static ItemStack buildTurnActionStack() {
		return AbilityItem.build(Items.FEATHER, "turnActions", Component.literal("Acciones de Turno"),
			Component.literal("Clic derecho: esquivar, correr o desengancharse.").withStyle(ChatFormatting.GRAY));
	}
}
