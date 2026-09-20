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

    //Public: used by SheetCommand (/dndsheet setroll) so a check/save/skill can be named
    //rather than requiring the DM to remember its numeric index.
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

    // --- Skill proficiencies ---------------------------------------------------------------

    /**
     * <p>5e's 18 skills, in the SAME order as the sheet's {@code skills} array and as
     * {@code CharacterSheetScreen}'s labels. They live here rather than in the screen because the index is
     * what decides which skill gets the proficiency: two lists in two files getting out of sync doesn't
     * throw an error, it gives Stealth proficiency to whoever picked Athletics.</p>
     */
    private static final String[] SKILL_KEYS = {
        "athletics", "acrobatics", "sleightofhand", "stealth", "arcana", "history", "investigation",
        "nature", "religion", "animalhandling", "insight", "medicine", "perception", "survival",
        "deception", "intimidation", "performance", "persuasion"
    };

    public static final int SKILL_COUNT = 18;

    //Derived from the array above, not a bare "12" in RollAnnouncerProcedure: if SKILL_KEYS ever
    //changes order, this index moves with it automatically instead of ending up pointing at another skill.
    public static final int PERCEPTION_SKILL_INDEX = java.util.Arrays.asList(SKILL_KEYS).indexOf("perception");

    /** The token the roll calculator already understands as "add your proficiency bonus". */
    public static final String PROFICIENCY_TOKEN = "$prof";

    //The two ways the term can appear written. The second form (at the start of the expression)
    //exists only so it can be REMOVED: nobody writes it that way from the UI, but an expression a DM
    //typed by hand starting with $prof still needs to be uncheckable, or the checkbox says one thing
    //and the roll does another. (?![a-z]) protects $hprof, which is half proficiency and isn't this.
    private static final java.util.regex.Pattern PROFICIENCY_TERM = java.util.regex.Pattern.compile(
        "(\\s*[+]\\s*[$]prof(?![a-z])|[$]prof(?![a-z])\\s*[+]\\s*)");

    public static String skillLangKey(int index) {
        return "gui.dndsheets.character_sheet.label_skill_" + SKILL_KEYS[index];
    }

    /**
     * <p>Each skill's ability, by range: Athletics is Strength; Acrobatics, Sleight of Hand and
     * Stealth are Dexterity; the five knowledge skills are Intelligence; the five perception skills are
     * Wisdom; the four social skills are Charisma. This is the SRD table and the order above follows it,
     * so it's read off the ranges instead of repeating it entry by entry.</p>
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
     * <p>Adds or removes the proficiency term <b>without touching the rest of the expression</b>. Rewriting
     * it entirely from the ability score would be a shorter line, and would erase any bonus the player or
     * the DM had manually added to that skill — which is exactly what a roll editor exists to allow.</p>
     */
    public static String withProficiency(String expression, boolean proficient) {
        String base = PROFICIENCY_TERM.matcher(expression == null ? "" : expression).replaceAll("").trim();
        return proficient ? base + " + " + PROFICIENCY_TOKEN : base;
    }

    public static boolean isSkillProficient(JsonObject sheet, int index) {
        String expression = skillExpression(sheet, index);
        return expression != null && isProficient(expression);
    }

    /** @return true if something changed; false if the index doesn't exist on this sheet. */
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
