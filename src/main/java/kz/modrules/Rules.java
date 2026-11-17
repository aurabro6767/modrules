package kz.modrules;

import java.util.*;
import java.util.regex.*;

public final class Rules {
    public record Punishment(String type, String spec) {
        @Override public String toString() { return type + " " + spec; }
    }
    public record Rule(String id, String title, List<Punishment> punishments) {}

    public static final Map<String, Rule> ALL = new LinkedHashMap<>();

    private static void add(String id, String title, String... puns) {
        List<Punishment> list = new ArrayList<>();
        for (String p : puns) {
            int i = p.indexOf(' ');
            list.add(new Punishment(p.substring(0, i), p.substring(i + 1)));
        }
        ALL.put(id, new Rule(id, title, list));
    }

    static {
        add("2.1", "Оскорбления", "Мут 1-6h");
        add("2.1.1", "Оскорбления стаффа", "Мут 6-12h");
        add("2.1.2", "Оскорбление религии, страны или нации", "Мут 7d");
        add("2.2", "Доксинг", "Мут 7d", "Бан 7d");
        add("2.2.1", "Доксинг стаффа", "Бан 7-14d");
        add("2.3", "Реклама", "Мут 7d");
        add("2.4", "Обман", "Бан 7d");
        add("2.4.1", "Обман игроков", "Бан 7-30d");
        add("2.5", "Спам и капс", "Мут 15m");
        add("2.6", "Продажа и обмен за реальные деньги", "Бан навсегда");
        add("2.7", "Призыв к причинению вреда организму", "Мут 6h-7d", "Бан 7-30d");
        add("2.8", "Пропаганда деструктивного контента", "Мут 7d", "Бан 7d");
        add("3.1", "Помеха", "Кик 3/3", "Бан 1h-1d");
        add("3.2", "Умышленное затягивание боя", "Кик 3/3", "Бан 1-6h");
        add("3.2.1", "Умышленный выход с сервера при пвп", "Кик 3/3", "Бан 1-6h");
        add("3.3", "Багоюз", "Кик 1/1", "Бан 7d");
        add("3.4", "Чит-клиенты, запрещенные модификации, макросы", "Бан 30d");
        add("3.4.1", "Признание", "Бан 14d");
        add("3.5", "Альтинг", "Бан 30d");
        add("3.6", "Отказ от проверок", "Бан 30d");
        add("3.7", "Выключенные службы", "Бан 30d");
        add("3.8", "Чистка до проверки", "Бан 30d");
        add("3.9", "Передача аккаунта", "Бан 30d");
        add("3.10", "Кросстим", "Кик 1/1", "Бан 6h");
    }

    // ---- работа со сроками ----
    private static final Pattern DUR = Pattern.compile("(\\d+)([mhd])?");

    private static long unitSec(char u) {
        return switch (u) { case 'm' -> 60L; case 'h' -> 3600L; default -> 86400L; };
    }

    /** Возвращает {min, max} в секундах или null, если срока нет. */
    public static long[] parseRange(String spec) {
        if (!spec.matches(".*\\d.*") || spec.contains("/")) return null;
        String[] parts = spec.split("-");
        Character def = null;
        List<Matcher> ms = new ArrayList<>();
        for (String p : parts) {
            Matcher m = DUR.matcher(p.trim());
            if (!m.matches()) return null;
            if (m.group(2) != null) def = m.group(2).charAt(0);
            ms.add(m);
        }
        long a = secs(ms.get(0), def);
        long b = ms.size() > 1 ? secs(ms.get(1), def) : a;
        return new long[]{Math.min(a, b), Math.max(a, b)};
    }

    private static long secs(Matcher m, Character def) {
        char u = m.group(2) != null ? m.group(2).charAt(0) : def;
        return Long.parseLong(m.group(1)) * unitSec(u);
    }

    /** Срок вида 3h -> секунды, либо -1 если формат неверный. */
    public static long parseDuration(String s) {
        Matcher m = DUR.matcher(s.toLowerCase());
        if (!m.matches() || m.group(2) == null) return -1;
        return Long.parseLong(m.group(1)) * unitSec(m.group(2).charAt(0));
    }

    public static String fmt(long sec) {
        if (sec % 86400 == 0) return (sec / 86400) + "d";
        if (sec % 3600 == 0) return (sec / 3600) + "h";
        return (sec / 60) + "m";
    }
}
