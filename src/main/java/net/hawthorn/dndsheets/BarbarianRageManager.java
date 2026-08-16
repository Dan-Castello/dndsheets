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
 * <p>Furia del bárbaro: resistencia a daño físico (cortante/perforante/contundente/físico) y un bono al
 * daño de armas cuerpo a cuerpo con Fuerza que sube con el nivel ({@link #damageBonusFor}), durante
 * {@value #DURATION_ROUNDS} asaltos
 * (1 minuto real de 5e). Se activa con clic derecho en el Tótem de Furia
 * ({@code {dndsheets:{rage:true}}}, entregado con {@code /dndsheet rageitem}), igual de patrón que los
 * ítems de turno ({@link TurnItemManager}).</p>
 *
 * <p><b>Duración por asaltos, no solo por ticks reales</b>: si el modo turnos está activo cuando se activa
 * la Furia, la duración se cuenta en asaltos completos ({@link TurnManager#onRoundsPass}) — un combate
 * lento en tiempo real no debería "gastar" la Furia antes de tiempo, ni un combate rápido dejarla parada
 * de más. Fuera de modo turnos (juego libre, sin iniciativa), cae a temporizador real
 * ({@link DndsheetsMod#queueServerWork}). Se decide UNA vez, al activarse; si el modo turnos se
 * activa/desactiva a mitad de la Furia, no cambia de modo de conteo (simplificación deliberada).</p>
 *
 * <p><b>Otra simplificación deliberada</b>: en 5e de verdad la Furia tiene un número limitado de usos por
 * descanso largo (2 a nivel 1-2, más en niveles altos). Aquí no hay límite de usos — activar de nuevo con
 * la Furia ya puesta no hace nada raro, solo no reinicia el contador.</p>
 */
public class BarbarianRageManager {
	private static final int DURATION_ROUNDS = 10; //1 minuto de 5e = 10 asaltos.
	private static final int DURATION_TICKS = 20 * 60; //1 minuto real fuera de modo turnos.
	/**
	 * <p>Bono de daño de la Furia, por nivel de personaje (+2/+3/+4, ver {@link CharacterRules#rageDamageBonusFor}).</p>
	 *
	 * <p>Era una constante fija en +2. La progresión de un bárbaro <em>es</em> este número, así que
	 * congelarlo dejaba a uno de nivel 20 pegando igual que uno de nivel 1 salvo por el arma.</p>
	 */
	public static int damageBonusFor(ServerPlayer player) {
		return CharacterRules.rageDamageBonusFor(
			SheetLoader.characterLevelOf(SheetLoader.getServerSheet(player.getStringUUID()), player));
	}

	private static final Set<UUID> raging = ConcurrentHashMap.newKeySet();

	public static boolean isRaging(ServerPlayer player) {
		return raging.contains(player.getUUID());
	}

	public static void activate(ServerPlayer player) {
		if (!raging.add(player.getUUID())) return; //Ya estaba en furia: no reinicia el contador ni duplica el mensaje.

		UUID uuid = player.getUUID();
		MinecraftServer server = player.getServer();
		Runnable expire = () -> {
			if (raging.remove(uuid) && server != null) {
				ServerPlayer stillHere = server.getPlayerList().getPlayer(uuid);
				if (stillHere != null) stillHere.sendSystemMessage(Component.literal("Tu furia termina.").withStyle(ChatFormatting.GRAY));
			}
		};

		if (TurnManager.isActive()) {
			TurnManager.onRoundsPass(DURATION_ROUNDS, expire);
		} else {
			DndsheetsMod.queueServerWork(DURATION_TICKS, expire);
		}

		CombatFx.activate(player);
		player.sendSystemMessage(Component.literal("¡Entras en furia! Resistencia a daño físico y +" + damageBonusFor(player) + " de daño cuerpo a cuerpo con Fuerza.").withStyle(ChatFeedback.RESOURCE));
	}

	//--- Tótem de Furia: se activa desde AbilityItemDispatcher en vez de suscribirse a los 3 eventos de
	//interacción por separado — ver AUDIT_TECHNICAL.md M-EVT-1. Mismo patrón que los ítems de turno
	//(TurnItemManager). ---

	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (event.getEntity() instanceof ServerPlayer player) activate(player);
	}

	public static ItemStack buildRageItemStack() {
		return AbilityItem.build(Items.RED_DYE, "rage", Component.literal("Tótem de Furia"),
			Component.literal("Clic derecho: entra en furia " + DURATION_ROUNDS + " asaltos.").withStyle(ChatFormatting.GRAY));
	}
}
