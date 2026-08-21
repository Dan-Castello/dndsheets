package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * <p>Contrahechizo: mismo "listo, se activa solo cuando ayuda" que Escudo (ver {@link ShieldManager}), pero
 * reacciona a que OTRO lanzador (jugador o monstruo del DM) empiece a lanzar un hechizo cerca —
 * {@link SpellCastManager#handleCastRequest} y {@link MonsterActionManager} llaman a {@link #findCounterer}
 * justo antes de resolver el efecto.</p>
 *
 * <p><b>Simplificación deliberada</b>: en 5e real un Contrahechizo de nivel 3 anula automáticamente
 * hechizos de nivel 3 o menos, y contra hechizos más altos hace falta una prueba de característica (CD 10 +
 * nivel del hechizo) o gastar un espacio de nivel igual o mayor. Aquí el pool de espacios es plano, sin
 * niveles por ranura (mismo motivo que el dado fijo de Castigo Divino) — así que cualquier Contrahechizo
 * listo con un espacio disponible anula cualquier hechizo, sin tirada de por medio.</p>
 */
public class CounterspellManager {

	//Contrahechizo es un conjuro de nivel 3 en 5e.
	private static final int LEVEL = 3;
	private static final double RANGE = 30.0;

	//Se activa desde AbilityItemDispatcher en vez de suscribirse a RightClickItem por su cuenta.
	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		sheet.addProperty("counterspellReady", true);
		SheetLoader.saveAndSync(player, sheet);
		CombatFx.activate(player);
		player.sendSystemMessage(Component.translatable("chat.dndsheets.resource.counterspell_ready").withStyle(ChatFeedback.RESOURCE));
	}

	//Público: llamado justo antes de resolver el efecto de un hechizo, tanto si lo lanza un jugador
	//(SpellCastManager) como un monstruo del DM (MonsterActionManager). Busca el primer jugador cercano con
	//Contrahechizo listo, reacción disponible y espacios de conjuro; si lo encuentra, le gasta el espacio y
	//la reacción y devuelve su nombre (el llamador se encarga de anunciar el fallo y no resolver el efecto).
	//Null si nadie pudo contrarrestarlo.
	public static String findCounterer(Level level, Vec3 origin, Entity caster) {
		//El Contrahechizo de un jugador solo protege al grupo de un lanzador ENEMIGO (monstruo del DM) —
		//antes no miraba de dónde venía el hechizo, así que el Contrahechizo de CUALQUIER jugador anulaba
		//también el hechizo de OTRO jugador (p.ej. un ataque contra un goblin), aliado "protegiendo" sin
		//querer al enemigo. PvP de verdad entre jugadores no pasa por Contrahechizo a propósito.
		if (caster instanceof ServerPlayer) return null;

		AABB box = new AABB(origin, origin).inflate(RANGE);
		for (Entity candidate : level.getEntities((Entity) null, box, e -> e instanceof ServerPlayer && e != caster)) {
			ServerPlayer player = (ServerPlayer) candidate;
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			if (sheet == null || !sheet.has("counterspellReady") || !sheet.get("counterspellReady").getAsBoolean()) continue;

			//Contrahechizo es un conjuro de NIVEL 3, así que pide un espacio de nivel 3 o superior. Con la
			//bolsa única bastaba tener "un espacio", y un mago de nivel 1 podía contrarrestar.
			if (!SpellSlots.hasSlotFor(sheet, LEVEL) || !TurnManager.tryReact(player)) continue;

			SpellSlots.spend(sheet, LEVEL);
			//Se consume al dispararse, igual que cualquier otro recurso de un solo uso — sin esto se quedaba
			//"listo" para siempre y anulaba cualquier hechizo enemigo que pasara cerca en cualquier ronda
			//futura sin que el jugador tuviera que volver a prepararlo (se podía "spamear" pasivamente).
			sheet.addProperty("counterspellReady", false);
			SheetLoader.saveAndSync(player, sheet);
			return SheetLoader.characterNameOf(sheet, player);
		}
		return null;
	}

	public static ItemStack buildCounterspellStack() {
		return AbilityItem.build(ItemLook.COUNTERSPELL, "counterspellSpell", Component.literal("Contrahechizo"),
			Component.literal("Clic derecho: listo para anular el próximo hechizo que veas lanzar cerca.").withStyle(ChatFormatting.GRAY));
	}
}
