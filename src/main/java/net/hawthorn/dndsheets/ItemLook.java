package net.hawthorn.dndsheets;

import net.minecraft.world.item.ItemStack;

/**
 * <p>El aspecto de cada ítem del mod: su textura propia, en vez de un ítem de vanilla renombrado.</p>
 *
 * <p><b>Por qué hacía falta.</b> Todo lo que reparte este mod —la Vara de DM, los tótems de clase, los
 * báculos, las cartas de invocación— era un ítem de Minecraft con otro nombre: un palo, un tinte rojo, una
 * pata de conejo. Funcionaba, pero en la barra rápida de un jugador con doce cosas encima no se distingue
 * el Castigo Divino del polvo de piedraluminosa que tenía de antes, y "todo son lo mismo con otro nombre"
 * es exactamente el problema que se acaba de arreglar en el bestiario.</p>
 *
 * <p><b>Cómo.</b> Un único ítem registrado ({@code dndsheets:token}) más
 * {@code CustomModelData}, que es la forma que da Minecraft para esto. Ventajas sobre las dos
 * alternativas: no se toca <b>ningún</b> modelo de vanilla —sobrescribir {@code minecraft:item/compass}
 * para poner aquí un icono le cambiaría la brújula a todo el mundo, y la de vanilla tiene 32 variantes por
 * ángulo—, y no se registran veinte ítems que aparecerían en {@code /give} dando objetos sin su etiqueta
 * NBT, o sea muertos.</p>
 *
 * <p><b>El número viaja por posición, así que esto es SOLO-AÑADIR.</b> El {@code CustomModelData} es
 * {@code ordinal() + 1} y queda escrito dentro de cada ItemStack que ya exista en el mundo de alguien.
 * Insertar una constante en medio le cambia el icono a todo lo repartido hasta hoy. Constantes nuevas, al
 * final. Es el mismo trato que los mensajes de red (invariante 1).</p>
 *
 * <p>La textura de cada uno es {@code assets/dndsheets/textures/item/<nombre en minúsculas>.png}, dibujada
 * en {@code tools/generate_item_icons.py} — arte propio, porque el mod no puede redistribuir el de nadie.</p>
 */
public enum ItemLook {
	DM_WAND,
	MOVE_WAND,
	REST_KIT,
	TURN_NEXT,
	TURN_UNDO,
	TURN_ACTIONS,
	RAGE,
	SECOND_WIND,
	INSPIRATION,
	WILD_SHAPE,
	TWINNED,
	SMITE,
	HUNTERS_MARK,
	SHIELD,
	COUNTERSPELL,
	TURN_UNDEAD,
	HELP,
	STAFF,
	SUMMON_CARD;

	/** El valor que el modelo de {@code token.json} busca en su lista de overrides. */
	public int customModelData() {
		return ordinal() + 1;
	}

	/** {@code assets/dndsheets/textures/item/<esto>.png} y {@code .../models/item/<esto>.json}. */
	public String textureName() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}

	/** Pinta este aspecto sobre un stack ya construido. */
	public ItemStack applyTo(ItemStack stack) {
		stack.getOrCreateTag().putInt("CustomModelData", customModelData());
		return stack;
	}
}
