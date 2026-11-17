package kz.modrules;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Проигрывает звук, когда в чате кто-то материт родных (пункт 2.1). */
public final class FamilyAlert {

    private static boolean enabled = true;
    private static long lastAlert = 0;

    // Слова-родственники (начало слова). Охватывают формы, сленг и уменьшительные.
    private static final List<String> RELATIVES = List.of(
        // мама
        "мама", "мамк", "мамаш", "мамул", "мамоч", "мамуш", "маменьк", "мамин", "мамен",
        "мамки", "мамку", "маму", "мамы", "мамв", "матушк", "мать", "матерь",
        // папа
        "папа", "папаш", "папк", "папоч", "папул", "папин", "папу", "папы",
        "отец", "отц", "батя", "батю", "бате", "батьк", "батек", "бать", "батян",
        // родители в целом
        "родител", "предк", "родн", "родню", "родств", "кровн",
        // отчим, мачеха
        "отчим", "мачех", "падчериц", "пасынк",
        // бабушка, дедушка
        "бабк", "бабуль", "бабуш", "прабаб", "деда", "деду", "дедом", "дедуль",
        "дедушк", "дедов", "прадед",
        // братья, сёстры
        "брат", "сестр", "сестер", "сеструх", "двоюродн", "троюродн", "кузен", "кузин",
        // дяди, тёти, племянники
        "дяд", "тетк", "тетя", "тетю", "тете", "тетуш", "племянн",
        // муж, жена
        "жена", "жену", "жены", "женой", "женк", "женуш", "супруг", "супруж",
        "мужа", "мужу", "мужем", "муженек",
        // свёкры, тёщи и т.д.
        "свекр", "свекор", "тещ", "тесть", "тестя", "зять", "зятя", "невестк",
        "шурин", "золовк", "деверь",
        // дети
        "дочк", "дочь", "дочер", "дочур", "сын", "сынок", "сынк", "сынул",
        "внук", "внучк", "ребенк", "ребен", "детей", "детишк", "детк",
        // семья
        "семья", "семью", "семье", "семьи", "семьей", "семейк", "домочад");

    // Формы, которые считаем родственником только при точном совпадении
    // (чтобы не ловить "материал", "мамонт" и т.п.)
    private static final Set<String> RELATIVE_EXACT = Set.of(
        "матери", "матерью", "матерей", "матерям", "матерях",
        "мамой", "мамою", "мамо", "мамам");

    // Мат и грубые оскорбления (начало слова)
    private static final List<String> SWEAR_PREFIX = List.of(
        "еб", "уеб", "заеб", "наеб", "объеб", "выеб", "поеб", "отъеб", "въеб",
        "хуй", "хуе", "хуя", "хуи", "пизд", "пезд", "бля", "блят",
        "сука", "суки", "суку", "сучк", "сучар", "шлюх", "шалав", "шмар",
        "мраз", "гандон", "говн", "дерьм", "пидор", "пидар", "пидр", "педик",
        "трах", "дроч", "гнид", "падл", "тварь", "чмо", "залуп", "муд", "жоп",
        // оскорбления, типичные для выражений про семью
        "канав", "помойк", "подзабор", "дебил", "идиот", "даун", "урод");

    // Слова, которые считаем матом только при точном совпадении (чтобы не ловить "сосед")
    private static final Set<String> SWEAR_EXACT = Set.of(
        "сосал", "сосет", "сосешь", "сосу", "соси", "сосала", "сосали");

    private static final int WINDOW = 4; // насколько близко слова должны стоять

    private FamilyAlert() {}

    public static void init() {
        // Чат игроков
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> check(message.getString()));
        // Серверные сообщения (многие серверы шлют чат именно так)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) check(message.getString());
        });
    }

    public static void registerCommand(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommandManager.literal("famalert")
            .then(ClientCommandManager.literal("on").executes(c -> {
                enabled = true;
                c.getSource().sendFeedback(Component.literal("§aЗвуковой сигнал включён"));
                return 1;
            }))
            .then(ClientCommandManager.literal("off").executes(c -> {
                enabled = false;
                c.getSource().sendFeedback(Component.literal("§eЗвуковой сигнал выключен"));
                return 1;
            }))
            .then(ClientCommandManager.literal("test").executes(c -> {
                playSound();
                return 1;
            })));
    }

    private static void check(String raw) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        if (now - lastAlert < 1500) return; // не чаще раза в 1.5 сек
        if (matches(raw)) {
            lastAlert = now;
            playSound();
            Minecraft.getInstance().gui.getChat().addMessage(
                Component.literal("§c[ModRules] Возможный мат на родных: пункт 2.1"));
        }
    }

    private static void playSound() {
        Minecraft mc = Minecraft.getInstance();
        // Чтобы сменить звук, замените NOTE_BLOCK_PLING, например, на ANVIL_LAND
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.0F));
    }

    /** Есть ли в тексте мат рядом со словом-родственником. */
    static boolean matches(String raw) {
        String[] tokens = normalize(raw).split(" +");
        for (int i = 0; i < tokens.length; i++) {
            if (!isRelative(tokens[i])) continue;
            int from = Math.max(0, i - WINDOW);
            int to = Math.min(tokens.length - 1, i + WINDOW);
            for (int j = from; j <= to; j++) {
                if (j != i && isSwear(tokens[j])) return true;
            }
        }
        return false;
    }

    private static boolean isRelative(String t) {
        if (RELATIVE_EXACT.contains(t)) return true;
        for (String r : RELATIVES) if (t.startsWith(r)) return true;
        return false;
    }

    private static boolean isSwear(String t) {
        if (SWEAR_EXACT.contains(t)) return true;
        for (String s : SWEAR_PREFIX) if (t.startsWith(s)) return true;
        return false;
    }

    /** Нижний регистр, замена латиницы/цифр на похожие русские буквы, сжатие повторов. */
    private static String normalize(String raw) {
        String s = raw.replaceAll("§.", "").toLowerCase(Locale.ROOT).replace('ё', 'е');
        StringBuilder sb = new StringBuilder();
        char prev = 0;
        for (char ch : s.toCharArray()) {
            char c = switch (ch) {
                case 'a', '@' -> 'а';
                case 'e' -> 'е';
                case 'o', '0' -> 'о';
                case 'p' -> 'р';
                case 'c' -> 'с';
                case 'x' -> 'х';
                case 'y' -> 'у';
                case 'k' -> 'к';
                case 'm' -> 'м';
                case 't' -> 'т';
                case '3' -> 'з';
                case '6' -> 'б';
                default -> ch;
            };
            if (!Character.isLetter(c)) c = ' ';
            if (c == prev) continue; // "сссука" -> "сука"
            sb.append(c);
            prev = c;
        }
        return sb.toString();
    }
}
