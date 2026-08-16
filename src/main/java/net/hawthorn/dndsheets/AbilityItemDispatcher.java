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
		dispatch(event);
	}

	@SubscribeEvent
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		dispatch(event);
	}

	@SubscribeEvent
	public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag dndTag = dndTagOf(event.getItemStack());
		if (dndTag == null) return;

		//Los dos ítems que NECESITAN una criatura delante, y por eso solo existen en este evento: la Marca
		//del Cazador marca a quien señalas y la Inspiración se la das a otro jugador. Van antes que el
		//reparto común porque este evento es el único donde su clic significa algo.
		if (dndTag.getBoolean("hunterMark")) RangerHunterMarkManager.tryUse(event);
		else if (dndTag.getBoolean("bardicInspiration")) BardInspirationManager.tryUse(event);
		else dispatch(event, dndTag);
	}

	private static void dispatch(PlayerInteractEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		CompoundTag dndTag = dndTagOf(event.getItemStack());
		if (dndTag != null) dispatch(event, dndTag);
	}

	/**
	 * <p>Reparto común a los TRES eventos de interacción. Estos ítems se usan sobre uno mismo, así que da
	 * igual qué haya delante al pulsarlos.</p>
	 *
	 * <p>Antes esta cadena estaba copiada tres veces, una por evento, y las copias se habían separado:
	 * Castigo Divino, Hechizo Gemelo, Contrahechizo y Escudo solo estaban en la de "clic al aire". El
	 * resultado era que esos cuatro <b>no hacían nada si estabas mirando a un monstruo o a un bloque</b> —
	 * es decir, justo en combate, que es cuando se usan. Con una sola cadena, un ítem nuevo entra en los
	 * tres eventos por construcción y no por acordarse.</p>
	 */
	private static void dispatch(PlayerInteractEvent event, CompoundTag dndTag) {
		if (dndTag.getBoolean("restKit")) RestManager.tryOpenRestChoice(event);
		else if (dndTag.getBoolean("rage")) BarbarianRageManager.tryUse(event);
		else if (dndTag.getBoolean("counterspellSpell")) CounterspellManager.tryUse(event);
		else if (dndTag.getBoolean("shieldSpell")) ShieldManager.tryUse(event);
		else if (dndTag.getBoolean("turnNext")) TurnItemManager.tryUse(event, true);
		else if (dndTag.getBoolean("turnUndo")) TurnItemManager.tryUse(event, false);
		else if (dndTag.getBoolean("secondWind")) FighterSecondWindManager.tryUse(event);
		else if (dndTag.getBoolean("turnUndead")) ClericTurnUndeadManager.tryUse(event);
		else if (dndTag.getBoolean("turnActions")) TurnActionManager.tryUse(event);
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

	private static CompoundTag dndTagOf(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains("dndsheets")) return null;
		return tag.getCompound("dndsheets");
	}
}
