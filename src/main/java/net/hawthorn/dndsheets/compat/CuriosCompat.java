package net.hawthorn.dndsheets.compat;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * <p>Integración <b>opcional</b> con Curios API. Resuelve un problema que la mesa de D&amp;D no tiene y
 * Minecraft sí: no hay ranura de anillo, collar, capa ni cinturón, así que un Anillo de Protección no
 * podía estar "puesto" de ninguna forma natural. Con Curios instalado, los objetos mágicos se llevan en
 * sus ranuras reales; sin él, todo sigue funcionando exactamente igual que antes vía sintonización.</p>
 *
 * <p><b>Por qué son dos clases y no una.</b> Esta no importa ni un solo tipo de Curios: si lo hiciera, la
 * JVM intentaría resolverlos al cargarla y reventaría con {@code NoClassDefFoundError} en cualquier
 * instalación sin Curios — que es justo lo que "dependencia blanda" tiene que evitar. Todo lo que toca su
 * API vive en {@link CuriosSlots}, que solo se carga después de comprobar {@link #isLoaded()}, porque
 * Java carga las clases de forma perezosa. La comprobación no es una cortesía: es lo que hace que la
 * separación funcione.</p>
 *
 * <p>En el build, Curios entra como {@code compileOnly} (más {@code runtimeOnly} solo para poder probarlo
 * en el entorno de desarrollo), y en {@code mods.toml} como dependencia con {@code mandatory=false}.
 * Nunca se empaqueta dentro del jar publicado.</p>
 */
public final class CuriosCompat {

	private CuriosCompat() {}

	//Se resuelve una sola vez: ModList no cambia después del arranque, y esto se consulta en cada cálculo
	//de CA de cada ataque.
	private static final boolean LOADED = ModList.get().isLoaded("curios");

	public static boolean isLoaded() {
		return LOADED;
	}

	/**
	 * <p>Lo que el jugador lleva en ranuras de Curios, o una lista vacía si Curios no está instalado.
	 * Devolver vacío en vez de fallar es lo que permite al llamador tratar los dos casos igual.</p>
	 */
	public static List<ItemStack> equippedStacks(Player player) {
		if (!LOADED) return List.of();
		return CuriosSlots.equippedStacks(player);
	}
}
