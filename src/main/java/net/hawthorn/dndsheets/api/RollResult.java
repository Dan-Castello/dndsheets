package net.hawthorn.dndsheets.api;

import net.hawthorn.dndsheets.DiceManager;

/**
 * <p>Resultado de {@link DndSheetsApi#roll}, propio de dndsheets — no reexporta el tipo {@code DiceResult}
 * de la librería externa {@code dicebot} para que un cambio/sombreado de esa dependencia no pueda romper
 * este contrato sin que {@link DndSheetsApi#API_VERSION} lo refleje.</p>
 */
public record RollResult(int total, String formatted) {
	/** @return {@code null} si la expresión fue rechazada (sintaxis inválida o conteo de dados absurdo). */
	static RollResult from(DiceManager.RollOutcome outcome) {
		if (outcome == null || outcome.result() == null) return null;
		return new RollResult(outcome.result().getValue(), outcome.formatted());
	}
}
