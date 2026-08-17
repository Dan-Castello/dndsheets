package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>Forma Salvaje del druida: mientras está activa, un golpe a mano desnuda se resuelve como zarpazo real
 * de 5e ({@value #DICE} por Fuerza) en vez del golpe flojo de Minecraft — mismo mecanismo que Artes
 * Marciales del monje ({@link TraitRegistry#unarmedProfileFor}), pero TEMPORAL en vez de una pasiva
 * permanente, así que vive en su propio gestor en vez de en {@code TraitRegistry}. Dura {@value
 * #DURATION_ROUNDS} asaltos (o el temporizador real equivalente fuera de modo turnos) — mismo patrón de
 * duración por asaltos/ticks que la Furia del bárbaro, ver {@link BarbarianRageManager}.</p>
 *
 * <p><b>Simplificación deliberada, y grande</b>: esto NO es Forma Salvaje de verdad — no cambia el modelo
 * del jugador, ni le da un bloque de estadísticas de bestia propio, ni una reserva de PG aparte (todo eso
 * necesitaría una transformación real de entidad, un proyecto mucho más grande). Es, literalmente, "las
 * manos desnudas del druida pegan como un animal mientras esto esté activo" — captura la sensación de
 * "puedo pelear sin arma" sin la transformación completa. Documentado aquí para que quede claro que es un
 * punto de partida, no la mecánica completa.</p>
 */
public class DruidWildShapeManager {
	private static final String DICE = "1d6"; //Zarpazo/mordisco genérico; 5e varía según la bestia elegida.
	private static final String ABILITY = "str";
	private static final int DURATION_ROUNDS = 10; //1 hora de 5e simplificada a 10 asaltos, igual que la Furia.
	private static final int DURATION_TICKS = 20 * 60;

	private static final Set<UUID> shifted = ConcurrentHashMap.newKeySet();

	/** Devuelve al druida a su forma sin avisar: la usa el cambio de personaje. Ver SheetLoader. */
	public static void clearFor(ServerPlayer player) {
		shifted.remove(player.getUUID());
	}

	public static boolean isShifted(ServerPlayer player) {
		return shifted.contains(player.getUUID());
	}

	public static TraitRegistry.UnarmedProfile unarmedProfile() {
		return new TraitRegistry.UnarmedProfile(DICE, ABILITY);
	}

	public static void activate(ServerPlayer player) {
		if (!shifted.add(player.getUUID())) return;
		CombatFx.activate(player);

		UUID uuid = player.getUUID();
		MinecraftServer server = player.getServer();
		Runnable expire = () -> {
			if (shifted.remove(uuid) && server != null) {
				ServerPlayer stillHere = server.getPlayerList().getPlayer(uuid);
				if (stillHere != null) stillHere.sendSystemMessage(Component.literal("Vuelves a tu forma normal.").withStyle(ChatFormatting.GRAY));
			}
		};

		if (TurnManager.isActive()) {
			TurnManager.onRoundsPass(DURATION_ROUNDS, expire);
		} else {
			DndsheetsMod.queueServerWork(DURATION_TICKS, expire);
		}

		player.sendSystemMessage(Component.literal("¡Adoptas Forma Salvaje! Tus golpes a mano desnuda son zarpazos reales.").withStyle(ChatFeedback.RESOURCE));
	}

	//--- Ítem de Forma Salvaje: se activa desde AbilityItemDispatcher en vez de suscribirse a los 3 eventos
	//de interacción por separado — ver AUDIT_TECHNICAL.md M-EVT-1. Mismo patrón que el Tótem de Furia
	//(BarbarianRageManager). ---

	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (event.getEntity() instanceof ServerPlayer player) activate(player);
	}

	public static ItemStack buildWildShapeStack() {
		return AbilityItem.build(ItemLook.WILD_SHAPE, "wildShape", Component.literal("Forma Salvaje"),
			Component.literal("Clic derecho: golpes a mano desnuda pegan como un animal " + DURATION_ROUNDS + " asaltos.").withStyle(ChatFormatting.GRAY));
	}
}
