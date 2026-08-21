package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;

public class RollIndex {

    private static final Logger log = LogManager.getLogger(DndsheetsMod.MODID);

    public enum Category {
        CHECKS(0) {
            @Override
            public String toString() { return "checks"; }
        },
        SAVES(1) {
            @Override
            public String toString() { return "saves"; }
        },
        SKILLS(2) {
            @Override
            public String toString() { return "skills"; }
        },
        ATTACKS(3) {
            @Override
            public boolean isAdvanced() {return true;}
            @Override
            public String toString() { return "attacks"; }
        };

        public boolean isAdvanced() {return false;}

        private final int catNum;
        Category (int catNum) {
            this.catNum = catNum;
        }
        public int getInt() {
            return catNum;
        }
        public static Category fromInt(int value) {
            return switch(value) {
                case 0 -> Category.CHECKS;
                case 1 -> Category.SAVES;
                case 2 -> Category.SKILLS;
                case 3 -> Category.ATTACKS;
                default -> throw new IllegalStateException("Unexpected value: " + value);
            };
        }
    }
    private final Category category;
    private final int index;
    private final int subIndex;

    public RollIndex(int category, int index, int subIndex) {
        this.category = Category.fromInt(category);
        this.index = index;
        this.subIndex = subIndex;
    }

    public RollIndex(int category, int index) {
        this.category = Category.fromInt(category);
        this.index = index;
        this.subIndex = 0;
    }

    public RollIndex(Category category, int index, int subIndex) {
        this.category = category;
        this.index = index;
        this.subIndex = subIndex;
    }

    public RollIndex(Category category, int index) {
        this.category = category;
        this.index = index;
        this.subIndex = 0;
    }

    public Category getCategory() {
        return category;
    }
    public int getIndex() {
        return index;
    }

    public List<String> findContextsInSheet(JsonObject sheet) {

        JsonArray arr = sheet.getAsJsonArray(category.toString());
        List<String> output = new ArrayList<>();

        if (category.isAdvanced()) {
            JsonObject rollForm = arr.get(index).getAsJsonObject();
            JsonArray rollSet = rollForm.getAsJsonArray("rolls");
            JsonArray rollGroup = rollSet.get(subIndex).getAsJsonArray();

            rollGroup.forEach((item) -> {
                JsonObject roll = item.getAsJsonObject();
                output.add(roll.get("context").getAsString());
            });
        }
        else {

            output.add(getBasicContext());
            log.info(output);
        }

        return output;
    }

    public List<String> findExpressionsInSheet(JsonObject sheet) {

        JsonArray arr = sheet.getAsJsonArray(category.toString());
        List<String> output = new ArrayList<>();

        if (category.isAdvanced()) {
            JsonObject rollForm = arr.get(index).getAsJsonObject();
            JsonArray rollSet = rollForm.getAsJsonArray("rolls");
            JsonArray rollGroup = rollSet.get(subIndex).getAsJsonArray();

            rollGroup.forEach((item) -> {
                JsonObject roll = item.getAsJsonObject();
                output.add(roll.get("expression").getAsString());
            });
        }
        else {
            String expression = arr.get(index).getAsString();

            output.add(expression);
        }

        return output;
    }

    public void saveInSheet(JsonObject sheet, String expression) {
        if (category.isAdvanced()) return;

        try {
            SheetLoader.validateSheet(sheet);
            JsonArray arr = sheet.getAsJsonArray(category.toString());
            if (index >= arr.size()) arr.add(expression);
            else arr.set(index, new JsonPrimitive(expression));

            sheet.add(category.toString(), arr);

        }
        catch (Exception e) {
            log.error("e: ", e);
        }
    }

    public void saveInSheet(JsonObject sheet, List<AbstractMap.SimpleEntry<String, String>> data) {
        saveInSheet(sheet, data, "");
    }

    public void saveInSheet(JsonObject sheet, String formName, boolean isSavingName) {
        if (!isSavingName) saveInSheet(sheet, formName);

        if (!category.isAdvanced()) return;

        try {
            SheetLoader.validateSheet(sheet);
            JsonArray arr = sheet.getAsJsonArray(category.toString());

            JsonObject rollForm; //Entire form, with a name and the 2d rolls array.
            JsonArray rollSet; //Sets of groups of rolls. This is a 2d array.

            if (index >= arr.size()) {
                rollForm = new JsonObject();
                rollSet = new JsonArray();
                rollForm.add("rolls", rollSet);
            }
            else {
                rollForm = arr.get(index).getAsJsonObject();
            }

            if (!formName.isBlank()) {
                rollForm.addProperty("name", formName);
            }

            if (index >= arr.size()) {
                arr.add(rollForm);
            }
            else {
                arr.set(index, rollForm);
            }

            sheet.add(category.toString(), arr);

        }
        catch(Exception e) {
            log.error("e: ", e);
        }
    }

    public void saveInSheet(JsonObject sheet, List<AbstractMap.SimpleEntry<String, String>> data, String formName) {
        if (!category.isAdvanced()) return;

        try {
            SheetLoader.validateSheet(sheet);
            JsonArray arr = sheet.getAsJsonArray(category.toString());

            JsonObject rollForm; //Entire form, with a name and the 2d rolls array.
            JsonArray rollSet; //Sets of groups of rolls. This is a 2d array.
            JsonArray rollGroup; //Group of rolls that get rolled together when sent through the roll announcer

            if (index >= arr.size()) {
                rollForm = new JsonObject();
                rollSet = new JsonArray();
                rollForm.add("rolls", rollSet);
            }
            else {
                rollForm = arr.get(index).getAsJsonObject();
                rollSet = rollForm.getAsJsonArray("rolls");
            }

            if (!rollForm.has("name") || !formName.isBlank()) {
                rollForm.addProperty("name", formName);
            }

            if (subIndex >= rollSet.size()) {
                rollGroup = new JsonArray();
                data.forEach((pair) -> {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("context", pair.getKey());
                    obj.addProperty("expression", pair.getValue());
                    rollGroup.add(obj);
                });
                rollSet.add(rollGroup);
            }
            else {
                rollGroup = new JsonArray();
                data.forEach((pair) -> {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("context", pair.getKey());
                    obj.addProperty("expression", pair.getValue());
                    rollGroup.add(obj);
                });
                rollSet.set(subIndex, rollGroup);
            }

            rollForm.add("rolls", rollSet);
            if (index >= arr.size()) {
                arr.add(rollForm);
            }
            else {
                arr.set(index, rollForm);
            }

            sheet.add(category.toString(), arr);

        }
        catch (Exception e) {
            log.error("e: ", e);
        }
    }

    //Público: usado por SheetCommand (/dndsheet setroll) para poder nombrar un check/save/skill por su
    //nombre en vez de exigir que el DM se acuerde de su índice numérico.
    public static List<String> basicNames(Category category) {
        int count = switch (category) {
            case CHECKS -> 7;
            case SAVES -> 6;
            case SKILLS -> 18;
            case ATTACKS -> 0;
        };
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) names.add(new RollIndex(category, i).getBasicContext());
        return names;
    }

    /**
     * <p>This won't return anything if the category is of an advanced type.</p>
     * @return
     */
    public String getBasicContext() {
        String result = "";
        result = switch (category.getInt()) {
            case 0 -> switch (index) {
                case 0 -> "Strength Check";
                case 1 -> "Dexterity Check";
                case 2 -> "Constitution Check";
                case 3 -> "Intelligence Check";
                case 4 -> "Wisdom Check";
                case 5 -> "Charisma Check";
                case 6 -> "Initiative";
                default -> result;
            };
            case 1 -> switch (index) {
                case 0 -> "Strength Save";
                case 1 -> "Dexterity Save";
                case 2 -> "Constitution Save";
                case 3 -> "Intelligence Save";
                case 4 -> "Wisdom Save";
                case 5 -> "Charisma Save";
                default -> result;
            };
            case 2 -> switch (index) {
                case 0 -> "Athletics Check";
                case 1 -> "Acrobatics Check";
                case 2 -> "Sleight of Hand Check";
                case 3 -> "Stealth Check";
                case 4 -> "Arcana Check";
                case 5 -> "History Check";
                case 6 -> "Investigation Check";
                case 7 -> "Nature Check";
                case 8 -> "Religion Check";
                case 9 -> "Animal Handling Check";
                case 10 -> "Insight Check";
                case 11 -> "Medicine Check";
                case 12 -> "Perception Check";
                case 13 -> "Survival Check";
                case 14 -> "Deception Check";
                case 15 -> "Intimidation Check";
                case 16 -> "Performance Check";
                case 17 -> "Persuasion Check";
                default -> result;
            };
            default -> "";
        };
        return result;
    }

    // --- Competencias de habilidad ---------------------------------------------------------------

    /**
     * <p>Las 18 habilidades de 5e, en el MISMO orden que el array {@code skills} de la hoja y que las
     * etiquetas de {@code CharacterSheetScreen}. Viven aquí y no en la pantalla porque el índice es lo que
     * decide qué habilidad recibe la competencia: dos listas en dos archivos que se desordenen entre sí no
     * dan un error, dan competencia en Sigilo a quien eligió Atletismo.</p>
     */
    private static final String[] SKILL_KEYS = {
        "athletics", "acrobatics", "sleightofhand", "stealth", "arcana", "history", "investigation",
        "nature", "religion", "animalhandling", "insight", "medicine", "perception", "survival",
        "deception", "intimidation", "performance", "persuasion"
    };

    public static final int SKILL_COUNT = 18;

    /** El token que la calculadora de tiradas ya entiende como "suma tu bono de competencia". */
    public static final String PROFICIENCY_TOKEN = "$prof";

    //Las dos formas en que el término puede aparecer escrito. La segunda (al principio de la expresión)
    //existe solo para poder QUITARLA: nadie la escribe desde la interfaz, pero una expresión escrita a mano
    //por un DM que empiece por $prof tiene que poder desmarcarse igual, o la casilla dice una cosa y la
    //tirada hace otra. (?![a-z]) protege a $hprof, que es media competencia y no es esto.
    private static final java.util.regex.Pattern PROFICIENCY_TERM = java.util.regex.Pattern.compile(
        "(\\s*[+]\\s*[$]prof(?![a-z])|[$]prof(?![a-z])\\s*[+]\\s*)");

    public static String skillLangKey(int index) {
        return "gui.dndsheets.character_sheet.label_skill_" + SKILL_KEYS[index];
    }

    /**
     * <p>La característica de cada habilidad, por tramos: Atletismo es de Fuerza; Acrobacias, Juego de
     * Manos y Sigilo de Destreza; las cinco de conocimiento de Inteligencia; las cinco de percepción de
     * Sabiduría; las cuatro sociales de Carisma. Es la tabla del SRD y el orden de arriba la sigue, así que
     * se lee de los tramos en vez de repetirla entrada por entrada.</p>
     */
    public static String skillAbility(int index) {
        if (index <= 0) return "str";
        if (index <= 3) return "dex";
        if (index <= 8) return "int";
        if (index <= 13) return "wis";
        return "cha";
    }

    public static boolean isProficient(String expression) {
        return expression != null && expression.contains(PROFICIENCY_TOKEN);
    }

    /**
     * <p>Añade o quita el término de competencia <b>sin tocar el resto de la expresión</b>. Reescribirla
     * entera desde la característica sería una línea más corta y borraría cualquier bono que el jugador o
     * el DM hubieran puesto a mano en esa habilidad, que es justo lo que un editor de tiradas existe para
     * permitir.</p>
     */
    public static String withProficiency(String expression, boolean proficient) {
        String base = PROFICIENCY_TERM.matcher(expression == null ? "" : expression).replaceAll("").trim();
        return proficient ? base + " + " + PROFICIENCY_TOKEN : base;
    }

    public static boolean isSkillProficient(JsonObject sheet, int index) {
        String expression = skillExpression(sheet, index);
        return expression != null && isProficient(expression);
    }

    /** @return true si algo cambió; false si el índice no existe en esta hoja. */
    public static boolean setSkillProficiency(JsonObject sheet, int index, boolean proficient) {
        String expression = skillExpression(sheet, index);
        if (expression == null) return false;
        sheet.getAsJsonArray(Category.SKILLS.toString()).set(index, new JsonPrimitive(withProficiency(expression, proficient)));
        return true;
    }

    private static String skillExpression(JsonObject sheet, int index) {
        if (sheet == null || index < 0 || index >= SKILL_COUNT) return null;
        JsonArray skills = sheet.getAsJsonArray(Category.SKILLS.toString());
        if (skills == null || index >= skills.size()) return null;
        return skills.get(index).getAsString();
    }
}
