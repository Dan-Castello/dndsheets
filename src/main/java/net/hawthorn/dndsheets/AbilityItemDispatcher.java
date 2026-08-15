package net.hawthorn.dndsheets;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

//Despachador único para los ítems "botón" de un solo flag NBT que se activan con clic derecho (Kit de
//Descanso, Tótem de Furia, Contrahechizo, Escudo, Marca del Cazador, Segundo Aliento, Castigo Divino,
//Hechizo Gemelo, Forma Salvaje, Inspiración Bárdica, báculos de hechizo rápido, ítems de turno): antes
//cada manager se suscribía por separado a los mismos 3 eventos de interacción y releía el NBT de forma
//independiente (hasta 18+ handlers por clic derecho) — ver AUDIT_TECHNICAL.md M-EVT-1. Aquí se lee una
//sola vez y se delega al manager correspondiente. Cada rama de evento solo comprueba los flags de los
//managers que originalmente escuchaban ESE evento (p.ej. Marca del Cazador solo actuaba en EntityInteract,
//porque necesita el objetivo del clic) para no cambiarle el comportamiento a nadie. "quickSpell" es la
//excepción: no es un flag booleano sino un id de hechizo (String), así que se detecta con
//dndTag.contains(...) en vez de dndTag.getBoolean(...).
@Mod.EventBusSubscriber
public class AbilityItemDispatcher {

	@SubscribeEvent
	public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag dndTag = dndTagOf(event.getItemStack());
		if (dndTag == null) return;

		if (dndTag.getBoolean("restKit")) RestManager.tryOpenRestChoice(event);
		else if (dndTag.getBoolean("rage")) BarbarianRageManager.tryUse(event);
		else if (dndTag.getBoolean("counterspellSpell")) CounterspellManager.tryUse(event);
		else if (dndTag.getBoolean("shieldSpell")) ShieldManager.tryUse(event);
		else if (dndTag.getBoolean("turnNext")) TurnItemManager.tryUse(event, true);
		else if (dndTag.getBoolean("turnUndo")) TurnItemManager.tryUse(event, false);
		else if (dndTag.getBoolean("secondWind")) FighterSecondWindManager.tryUse(event);
		else if (dndTag.getBoolean("divineSmite")) PaladinSmiteManager.tryUse(event);
		else if (dndTag.getBoolean("twinnedSpell")) SorcererMetamagicManager.tryUse(event);
		else if (dndTag.getBoolean("wildShape")) DruidWildShapeManager.tryUse(event);
		//Antes que quickSpell: una varita que ADEMAS es consumible no existe hoy, pero si existiera, gastarla
		//debe ganar — lanzar sin gastarla seria darla infinita.
		else if (isConsumable(dndTag)) ConsumableManager.tryUse(event, dndTag.getString("magicItem"));
		else if (dndTag.contains("quickSpell")) QuickSpellManager.tryUse(event, dndTag.getString("quickSpell"));
	}

	//Un objeto magico solo entra por aqui si de verdad es consumible: los pasivos (anillos, capas) no
	//deben hacer nada al pulsarlos, y cancelar su evento impediria colocarlos en una ranura de Curios.
	private static boolean isConsumable(CompoundTag dndTag) {
		if (!dndTag.contains("magicItem")) return false;
		MagicItemRegistry.MagicItem item = MagicItemRegistry.get(dndTag.getString("magicItem"));
		return item != null && item.isConsumable();
	}

	@SubscribeEvent
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag dndTag = dndTagOf(event.getItemStack());
		if (dndTag == null) return;

		if (dndTag.getBoolean("restKit")) RestManager.tryOpenRestChoice(event);
		else if (dndTag.getBoolean("rage")) BarbarianRageManager.tryUse(event);
		else if (dndTag.getBoolean("turnNext")) TurnItemManager.tryUse(event, true);
		else if (dndTag.getBoolean("turnUndo")) TurnItemManager.tryUse(event, false);
		else if (dndTag.getBoolean("secondWind")) FighterSecondWindManager.tryUse(event);
		else if (dndTag.getBoolean("wildShape")) DruidWildShapeManager.tryUse(event);
		//Antes que quickSpell: una varita que ADEMAS es consumible no existe hoy, pero si existiera, gastarla
		//debe ganar — lanzar sin gastarla seria darla infinita.
		else if (isConsumable(dndTag)) ConsumableManager.tryUse(event, dndTag.getString("magicItem"));
		else if (dndTag.contains("quickSpell")) QuickSpellManager.tryUse(event, dndTag.getString("quickSpell"));
	}

	@SubscribeEvent
	public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag dndTag = dndTagOf(event.getItemStack());
		if (dndTag == null) return;

		if (dndTag.getBoolean("restKit")) RestManager.tryOpenRestChoice(event);
		else if (dndTag.getBoolean("rage")) BarbarianRageManager.tryUse(event);
		else if (dndTag.getBoolean("hunterMark")) RangerHunterMarkManager.tryUse(event);
		else if (dndTag.getBoolean("turnNext")) TurnItemManager.tryUse(event, true);
		else if (dndTag.getBoolean("turnUndo")) TurnItemManager.tryUse(event, false);
		else if (dndTag.getBoolean("secondWind")) FighterSecondWindManager.tryUse(event);
		else if (dndTag.getBoolean("wildShape")) DruidWildShapeManager.tryUse(event);
		else if (dndTag.getBoolean("bardicInspiration")) BardInspirationManager.tryUse(event);
		//Antes que quickSpell: una varita que ADEMAS es consumible no existe hoy, pero si existiera, gastarla
		//debe ganar — lanzar sin gastarla seria darla infinita.
		else if (isConsumable(dndTag)) ConsumableManager.tryUse(event, dndTag.getString("magicItem"));
		else if (dndTag.contains("quickSpell")) QuickSpellManager.tryUse(event, dndTag.getString("quickSpell"));
	}

	private static CompoundTag dndTagOf(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains("dndsheets")) return null;
		return tag.getCompound("dndsheets");
	}
}
