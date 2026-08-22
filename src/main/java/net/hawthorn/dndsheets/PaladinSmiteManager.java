package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
 * <p>Y suma <b>otro d8 contra no-muertos e inmundos</b>, que es lo que hace del paladín un cazador de
 * muertos vivientes y no un guerrero con dados de más. Esta parte estuvo sin escribir mientras un monstruo
 * no tuvo tipo de criatura: no había nada que consultar, y deducirlo del nombre habría acertado con el
 * esqueleto y fallado con todo lo demás. Ver {@link CreatureType}.</p>
 */
public class PaladinSmiteManager {
	/**
	 * <p>Dados del castigo: 2d8 de base, +1d8 por cada nivel de espacio por encima del 1º con tope en 5d8,
	 * y +1d8 más si la víctima es no-muerta o inmunda.</p>
	 *
	 * <p>El extra se suma <b>después</b> del tope a propósito: en 5e el límite de 5d8 es el de la subida
	 * por espacio, y el dado contra no-muertos va aparte — un castigo de 6d8 con un espacio de 4º sobre un
	 * esqueleto es la cifra correcta, no un desbordamiento.</p>
	 */
	static String diceForSlot(int slotLevel, CreatureType targetType) {
		int dice = Math.min(5, 1 + Math.max(1, slotLevel));
		if (targetType.isSmiteFavoredTarget()) dice++;
		return dice + "d8";
	}

	//Se activa desde AbilityItemDispatcher en vez de suscribirse a RightClickItem por su cuenta.
	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		sheet.addProperty("smitePending", true);
		SheetLoader.saveServer(sheet, player.getStringUUID());
		//Un flag armado que no se ve es un flag que se olvida: el paladín no sabía si le quedaba el castigo
		//preparado de hace tres turnos o si ya lo gastó.
		JsonObject patch = new JsonObject();
		patch.addProperty("smitePending", true);
		DndsheetsMod.sendSheetFieldUpdate(player, patch);
		CombatFx.activate(player);
		player.sendSystemMessage(Component.translatable("chat.dndsheets.resource.smite_armed").withStyle(ChatFeedback.RESOURCE));
	}

	//Público: CombatManager lo consume justo después de confirmar un golpe (no antes: fallar el ataque no
	//debería gastar el espacio). Devuelve null si no había flag pendiente O no quedaban espacios que gastar.
	//El objetivo entra porque el dado depende de contra QUÉ se castiga, no solo de con qué se paga.
	public static String consumeIfPending(JsonObject sheet, Entity target) {
		if (sheet == null || !sheet.has("smitePending") || !sheet.get("smitePending").getAsBoolean()) return null;
		sheet.remove("smitePending");

		//Cualquier espacio de nivel 1 o superior sirve; se gasta el más bajo. Y el dado sale del nivel que
		//spend() dice haber gastado, no del que se pidió: si los de nivel 1 estaban agotados, el castigo
		//salió con uno más alto y pega más.
		if (!SpellSlots.hasSlotFor(sheet, 1)) return null;
		return diceForSlot(SpellSlots.spend(sheet, 1), MonsterRegistry.typeOf(target));
	}

	public static ItemStack buildDivineSmiteStack() {
		return AbilityItem.build(ItemLook.SMITE, "divineSmite", Component.translatable("chat.dndsheets.smite.item_name"),
			Component.translatable("chat.dndsheets.smite.item_lore").withStyle(ChatFormatting.GRAY));
	}
}
