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
 * hechicero); el PRÓXIMO golpe de arma del paladín que conecte gasta un espacio de conjuro y suma daño
 * radiante — {@link CombatManager} lo tira aparte y suma el monto, igual que Ataque Furtivo/Marca del
 * Cazador, para no meter dos grupos de dados en la misma expresión.</p>
 *
 * <p>El dado <b>escala con el espacio que se gasta de verdad</b>: 2d8 con uno de nivel 1 y +1d8 por cada
 * nivel por encima, con tope en 5d8. Era fijo en 2d8 porque los espacios eran un contador plano sin
 * niveles; desde que {@link SpellSlots#spend} dice con qué nivel salió, la regla se puede escribir tal
 * cual. No hay que elegir nivel: se sigue cogiendo el más bajo que quede, así que el castigo crece solo
 * cuando al paladín ya no le quedan espacios baratos — que es exactamente cuando en la mesa se gasta uno
 * caro.</p>
 *
 * <p><b>Lo que sigue faltando</b>: el +1d8 extra contra no-muertos e inmundos. Un monstruo de este mod no
 * tiene tipo de criatura, así que no hay nada que consultar — inventarlo por el nombre acertaría con el
 * esqueleto y fallaría con todo lo demás.</p>
 */
public class PaladinSmiteManager {
	/** Dados del castigo con un espacio del nivel dado: 2d8 de base, +1d8 por nivel, tope 5d8 (SRD). */
	static String diceForSlot(int slotLevel) {
		return Math.min(5, 1 + Math.max(1, slotLevel)) + "d8";
	}

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

		//Cualquier espacio de nivel 1 o superior sirve; se gasta el más bajo. Y el dado sale del nivel que
		//spend() dice haber gastado, no del que se pidió: si los de nivel 1 estaban agotados, el castigo
		//salió con uno más alto y pega más.
		if (!SpellSlots.hasSlotFor(sheet, 1)) return null;
		return diceForSlot(SpellSlots.spend(sheet, 1));
	}

	public static ItemStack buildDivineSmiteStack() {
		return AbilityItem.build(Items.GLOWSTONE_DUST, "divineSmite", Component.literal("Castigo Divino"),
			Component.literal("Clic derecho: tu próximo golpe gasta un espacio y suma de 2d8 a 5d8 radiante, según el espacio.").withStyle(ChatFormatting.GRAY));
	}
}
