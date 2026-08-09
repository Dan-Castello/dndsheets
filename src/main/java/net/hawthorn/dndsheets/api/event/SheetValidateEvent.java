package net.hawthorn.dndsheets.api.event;

import com.google.gson.JsonObject;
import net.minecraftforge.eventbus.api.Event;

/**
 * <p>Se dispara al final de {@code SheetLoader.validateSheet(sheet)} — es decir, cada vez que una hoja se
 * carga desde disco o se crea por primera vez y ya tiene todos los campos por defecto del mod puestos.
 * Un mod de contenido puede suscribirse con {@code @SubscribeEvent} y usar {@link #getSheet()} para añadir
 * sus propios campos por defecto (p.ej. una estadística homebrew) sin tocar código de este mod.</p>
 * <p>No es cancelable: es un punto de "aportar datos", no de bloquear comportamiento.</p>
 * <p><b>Importante:</b> {@link #getSheet()} devuelve la referencia real de la hoja, no una copia — un
 * listener puede AÑADIR campos nuevos con seguridad, pero NUNCA debe borrar ni sobrescribir los campos
 * base que {@code validateSheet} ya rellenó (nombre, características, PG, etc.): dndsheets no vuelve a
 * validarlos después de disparar este evento, así que una hoja se puede quedar en un estado inválido
 * hasta que otro sistema falle al leer el campo que faltaba.</p>
 */
public class SheetValidateEvent extends Event {
	private final JsonObject sheet;

	public SheetValidateEvent(JsonObject sheet) {
		this.sheet = sheet;
	}

	/** @return la hoja real (no una copia) — ver la advertencia de la clase sobre qué se puede tocar. */
	public JsonObject getSheet() {
		return sheet;
	}
}
