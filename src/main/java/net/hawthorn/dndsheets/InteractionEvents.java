package net.hawthorn.dndsheets;

import net.minecraft.world.InteractionResult;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * <p>Un clic derecho no es un evento: son dos. Cuando la mano usada "no consume" nada, el cliente
 * reintenta por su cuenta con la otra mano (comportamiento vanilla de mano principal → secundaria) y
 * manda un segundo paquete, así que el servidor procesa la acción DOS VECES.</p>
 *
 * <p>Y {@code setCanceled(true)} por sí solo no lo evita: cancelar en el servidor deja el resultado en
 * {@code PASS}, que es justo lo que el cliente lee como "no consumió, prueba con la otra mano". Hay que
 * cancelar además con un resultado que SÍ consuma.</p>
 *
 * <p>Se ve como mensajes de chat duplicados, pero eso es solo el síntoma visible: lo que se ejecuta dos
 * veces es el manejador entero. Un manejador idempotente lo disimula —{@code onSelectMoveDestination}
 * borraba su estado en la primera pasada y salía temprano en la segunda, así que "movido" salía una sola
 * vez mientras "seleccionado" salía dos— y por eso conviene llamar a esto SIEMPRE, no solo donde se note.</p>
 */
final class InteractionEvents {

	private InteractionEvents() {
	}

	/** "Este clic derecho ya está atendido": cancela y corta el reintento del cliente con la otra mano. */
	static void consume(PlayerInteractEvent event) {
		event.setCanceled(true);
		event.setCancellationResult(InteractionResult.SUCCESS);
	}
}
