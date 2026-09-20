package net.hawthorn.dndsheets.api.event;

import com.google.gson.JsonObject;
import net.minecraftforge.eventbus.api.Event;

/**
 * <p>Fired at the end of {@code SheetLoader.validateSheet(sheet)} — that is, every time a sheet is loaded
 * from disk or created for the first time and already has all of the mod's default fields set. A content
 * mod can subscribe with {@code @SubscribeEvent} and use {@link #getSheet()} to add its own default
 * fields (e.g. a homebrew stat) without touching this mod's code.</p>
 * <p>Not cancelable: this is a "contribute data" point, not a place to block behavior.</p>
 * <p><b>Important:</b> {@link #getSheet()} returns the sheet's actual reference, not a copy — a listener
 * can safely ADD new fields, but must NEVER delete or overwrite the base fields that {@code validateSheet}
 * already filled in (name, ability scores, HP, etc.): dndsheets does not re-validate them after firing
 * this event, so a sheet can be left in an invalid state until some other system fails while reading the
 * missing field.</p>
 */
public class SheetValidateEvent extends Event {
	private final JsonObject sheet;

	public SheetValidateEvent(JsonObject sheet) {
		this.sheet = sheet;
	}

	/** @return the actual sheet (not a copy) — see the class warning about what may be touched. */
	public JsonObject getSheet() {
		return sheet;
	}
}
