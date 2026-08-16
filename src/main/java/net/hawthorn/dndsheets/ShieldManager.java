package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.network.SheetClientMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.network.PacketDistributor;

/**
 * <p>Escudo: clic derecho marca el hechizo como "listo" en la hoja (mismo patrón que Castigo Divino/
 * Hechizo Gemelo), pero a diferencia de esos NO se consume solo por dispararse una vez: en 5e de verdad se
 * decide lanzarlo ya sabiendo si el ataque entrante acertaría, así que aquí se comprueba justo donde ya se
 * compara la tirada de ataque contra la CA ({@link CombatManager#onLivingHurt},
 * {@link MonsterActionManager#resolveAttack}) y solo gasta espacio de conjuro + reacción cuando el +5 de
 * CA de verdad convierte un acierto en un fallo. Si el golpe iba a fallar igual, o acertaría de todas
 * formas incluso con Escudo, no se gasta nada y el flag sigue listo para el siguiente ataque de la ronda.</p>
 */
public class ShieldManager {

	//Escudo es un conjuro de nivel 1 en 5e.
	private static final int LEVEL = 1;
	private static final int AC_BONUS = 5;

	//Se activa desde AbilityItemDispatcher en vez de suscribirse a RightClickItem por su cuenta — ver
	//AUDIT_TECHNICAL.md M-EVT-1.
	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		sheet.addProperty("shieldReady", true);
		CombatFx.activate(player);
		player.sendSystemMessage(Component.literal("Escudo listo: se activará solo si un ataque te acertaría sin él.").withStyle(ChatFeedback.RESOURCE));
	}

	//Público: comprobado justo donde ya se compara la tirada de ataque contra la CA, tanto en PvP como en
	//un monstruo atacando a un jugador. Devuelve la CA a usar en ESA comparación: +5 si Escudo protegió de
	//verdad (y ya gastó el espacio + la reacción), la CA normal si no aplicaba o no hacía falta.
	public static int effectiveAc(ServerPlayer victim, int attackRollValue, int normalAc) {
		if (attackRollValue < normalAc || attackRollValue >= normalAc + AC_BONUS) return normalAc; //No cambiaría el resultado.

		JsonObject sheet = SheetLoader.getServerSheet(victim.getStringUUID());
		if (sheet == null || !sheet.has("shieldReady") || !sheet.get("shieldReady").getAsBoolean()) return normalAc;

		//Escudo es un conjuro de NIVEL 1: le sirve cualquier espacio, pero gasta el más bajo que tenga.
		if (!SpellSlots.hasSlotFor(sheet, LEVEL) || !TurnManager.tryReact(victim)) return normalAc;

		SpellSlots.spend(sheet, LEVEL);
		sendSheetUpdate(victim, sheet);
		victim.sendSystemMessage(Component.literal("¡Escudo! Tu CA sube a " + (normalAc + AC_BONUS) + " y el golpe falla.").withStyle(ChatFormatting.AQUA));
		return normalAc + AC_BONUS;
	}

	private static void sendSheetUpdate(ServerPlayer player, JsonObject sheet) {
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> player), new SheetClientMessage(sheet.toString().getBytes()));
	}

	public static ItemStack buildShieldStack() {
		return AbilityItem.build(Items.SHIELD, "shieldSpell", Component.literal("Escudo"),
			Component.literal("Clic derecho: listo para activarse solo cuando te salve de un golpe.").withStyle(ChatFormatting.GRAY));
	}
}
