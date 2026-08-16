package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * <p>Castigo Divino: clic derecho marca un flag de un solo uso (mismo patrón que Hechizo Gemelo del
 * hechicero); el PRÓXIMO golpe de arma del paladín que conecte gasta un espacio de conjuro y suma {@value
 * #DICE} de daño radiante — {@link CombatManager} lo tira aparte y suma el monto, igual que Ataque
 * Furtivo/Marca del Cazador, para no meter dos grupos de dados en la misma expresión.</p>
 *
 * <p><b>Simplificación deliberada</b>: en 5e de verdad el dado escala con el nivel del espacio gastado
 * (2d8 con uno de nivel 1, +1d8 por nivel de espacio por encima, +1d8 más contra no-muertos/inmundos) —
 * aquí el pool de espacios es un contador plano sin niveles por ranura, así que el dado es fijo en
 * {@value #DICE} sin importar qué espacio se gasta.</p>
 */
public class PaladinSmiteManager {
	public static final String DICE = "2d8";

	//Se activa desde AbilityItemDispatcher en vez de suscribirse a RightClickItem por su cuenta — ver
	//AUDIT_TECHNICAL.md M-EVT-1.
	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		sheet.addProperty("smitePending", true);
		CombatFx.activate(player);
		player.sendSystemMessage(Component.literal("Tu próximo golpe de arma que acierte gastará un espacio de conjuro para Castigo Divino.").withStyle(ChatFeedback.RESOURCE));
	}

	//Público: CombatManager lo consume justo después de confirmar un golpe (no antes: fallar el ataque no
	//debería gastar el espacio). Devuelve null si no había flag pendiente O no quedaban espacios que gastar.
	public static String consumeIfPending(JsonObject sheet) {
		if (sheet == null || !sheet.has("smitePending") || !sheet.get("smitePending").getAsBoolean()) return null;
		sheet.remove("smitePending");

		//Cualquier espacio de nivel 1 o superior sirve; se gasta el más bajo. Que el daño suba al usar uno
		//alto es otra regla y no está implementada.
		if (!SpellSlots.hasSlotFor(sheet, 1)) return null;
		SpellSlots.spend(sheet, 1);
		return DICE;
	}

	public static ItemStack buildDivineSmiteStack() {
		return AbilityItem.build(Items.GLOWSTONE_DUST, "divineSmite", Component.literal("Castigo Divino"),
			Component.literal("Clic derecho: tu próximo golpe gasta un espacio y suma " + DICE + " radiante.").withStyle(ChatFormatting.GRAY));
	}
}
