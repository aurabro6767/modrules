package kz.modrules;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModRulesClient implements ClientModInitializer {

    // Шаблоны команд вашего сервера (ник, срок, причина). Поменяйте под свой плагин.
    static final String MUTE = "mute %s %s %s";
    static final String TEMPBAN = "tempban %s %s %s";
    static final String PERMBAN = "ban %s %s";
    static final String KICK = "kick %s %s";

    // Счётчик киков: "ник|пункт" -> сколько киков уже выдано
    private static final Path COUNTS_FILE =
        FabricLoader.getInstance().getConfigDir().resolve("modrules-kicks.json");
    private static final Gson GSON = new Gson();
    private static Map<String, Integer> counts = loadCounts();

    private static Map<String, Integer> loadCounts() {
        try (Reader r = Files.newBufferedReader(COUNTS_FILE)) {
            Type t = new TypeToken<HashMap<String, Integer>>() {}.getType();
            Map<String, Integer> m = GSON.fromJson(r, t);
            return m != null ? m : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private static void saveCounts() {
        try (Writer w = Files.newBufferedWriter(COUNTS_FILE)) {
            GSON.toJson(counts, w);
        } catch (Exception ignored) { }
    }

    private static final SuggestionProvider<FabricClientCommandSource> RULE_IDS =
        (ctx, b) -> {
            Rules.ALL.keySet().forEach(b::suggest);
            return b.buildFuture();
        };

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((d, access) -> {
            register(d);
            FamilyAlert.registerCommand(d);
        });
        FamilyAlert.init();
    }

    private void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommandManager.literal("rules")
            .executes(c -> { listAll(c.getSource()); return 1; })
            .then(ClientCommandManager.argument("rule", StringArgumentType.word())
                .suggests(RULE_IDS)
                .executes(c -> { show(c.getSource(), arg(c, "rule")); return 1; })));

        // /punish <ник> <пункт> [авто|кик|мут|бан] [срок]
        RequiredArgumentBuilder<FabricClientCommandSource, String> duration =
            ClientCommandManager.argument("duration", StringArgumentType.word())
                .executes(c -> punish(c.getSource(), arg(c, "nick"), arg(c, "rule"),
                        arg(c, "type"), arg(c, "duration")));

        RequiredArgumentBuilder<FabricClientCommandSource, String> type =
            ClientCommandManager.argument("type", StringArgumentType.word())
                .suggests((c, b) -> {
                    b.suggest("авто"); b.suggest("кик"); b.suggest("мут"); b.suggest("бан");
                    return b.buildFuture();
                })
                .executes(c -> punish(c.getSource(), arg(c, "nick"), arg(c, "rule"), arg(c, "type"), null))
                .then(duration);

        d.register(ClientCommandManager.literal("punish")
            .then(ClientCommandManager.argument("nick", StringArgumentType.word())
                .then(ClientCommandManager.argument("rule", StringArgumentType.word())
                    .suggests(RULE_IDS)
                    .executes(c -> punish(c.getSource(), arg(c, "nick"), arg(c, "rule"), null, null))
                    .then(type))));

        // /punishreset <ник> <пункт> — обнулить счётчик киков
        d.register(ClientCommandManager.literal("punishreset")
            .then(ClientCommandManager.argument("nick", StringArgumentType.word())
                .then(ClientCommandManager.argument("rule", StringArgumentType.word())
                    .suggests(RULE_IDS)
                    .executes(c -> {
                        counts.remove(key(arg(c, "nick"), arg(c, "rule")));
                        saveCounts();
                        msg(c.getSource(), "§aСчётчик киков сброшен");
                        return 1;
                    }))));
    }

    private static String arg(CommandContext<FabricClientCommandSource> c, String n) {
        return StringArgumentType.getString(c, n);
    }

    private static String key(String nick, String rule) {
        return nick.toLowerCase() + "|" + rule;
    }

    private static void msg(FabricClientCommandSource s, String text) {
        s.sendFeedback(Component.literal(text));
    }

    private static void listAll(FabricClientCommandSource s) {
        Rules.ALL.values().forEach(r -> msg(s, r.id() + " " + r.title() + " -> " + r.punishments()));
    }

    private static void show(FabricClientCommandSource s, String id) {
        Rules.Rule r = Rules.ALL.get(id);
        if (r == null) { msg(s, "§cТакого пункта нет. Используйте /rules"); return; }
        msg(s, "§e" + r.id() + " - " + r.title());
        r.punishments().forEach(p -> msg(s, " • " + p));
    }

    private static String normType(String t) {
        if (t == null) return null;
        return switch (t.toLowerCase()) {
            case "авто", "auto" -> "авто";
            case "мут", "mute" -> "Мут";
            case "бан", "ban" -> "Бан";
            case "кик", "kick" -> "Кик";
            default -> t;
        };
    }

    private static Rules.Punishment find(List<Rules.Punishment> pool, String type) {
        return pool.stream().filter(x -> x.type().equals(type)).findFirst().orElse(null);
    }

    /** "3/3" -> 3 */
    private static int kickLimit(Rules.Punishment kick) {
        String[] p = kick.spec().split("/");
        return Integer.parseInt(p[p.length - 1].trim());
    }

    /** Собирает команду мута/бана. Возвращает null и пишет ошибку, если срок не подошёл. */
    private static String buildTimed(FabricClientCommandSource s, Rules.Punishment p, String nick,
                                     String id, String reason, String durArg) {
        if (p.spec().startsWith("навсегда")) return PERMBAN.formatted(nick, reason);

        long[] range = Rules.parseRange(p.spec());
        long sec;
        if (durArg == null) {
            if (range[0] != range[1]) {
                msg(s, "§cУкажите срок " + Rules.fmt(range[0]) + " - " + Rules.fmt(range[1])
                        + ". Пример: /punish " + nick + " " + id + " " + p.type().toLowerCase()
                        + " " + Rules.fmt(range[0]));
                return null;
            }
            sec = range[0];
        } else {
            sec = Rules.parseDuration(durArg);
            if (sec < 0) { msg(s, "§cСрок нужно писать так: 30m, 3h, 7d"); return null; }
            if (sec < range[0] || sec > range[1]) {
                msg(s, "§cСрок вне диапазона: " + Rules.fmt(range[0]) + " - " + Rules.fmt(range[1]));
                return null;
            }
        }
        String dur = Rules.fmt(sec);
        return p.type().equals("Мут")
                ? MUTE.formatted(nick, dur, reason)
                : TEMPBAN.formatted(nick, dur, reason);
    }

    private static int punish(FabricClientCommandSource s, String nick, String id,
                              String typeArg, String durArg) {
        Rules.Rule rule = Rules.ALL.get(id);
        if (rule == null) { msg(s, "§cТакого пункта нет. Используйте /rules"); return 0; }

        var conn = Minecraft.getInstance().getConnection();
        if (conn == null) { msg(s, "§cВы не подключены к серверу"); return 0; }

        List<Rules.Punishment> pool = rule.punishments();
        String reason = rule.id() + " - " + rule.title();
        String key = key(nick, id);
        String wanted = normType(typeArg);
        boolean auto = wanted == null || wanted.equals("авто");

        Rules.Punishment kick = find(pool, "Кик");
        Rules.Punishment p;

        if (auto) {
            if (kick != null) {
                int limit = kickLimit(kick);
                int used = counts.getOrDefault(key, 0);
                if (used < limit) {
                    conn.sendCommand(KICK.formatted(nick, reason));
                    counts.put(key, used + 1);
                    saveCounts();
                    msg(s, "§aКик " + (used + 1) + "/" + limit + " по пункту " + id + " для " + nick);
                    if (used + 1 == limit) {
                        msg(s, "§eСледующее нарушение: " + nextAfterKick(pool));
                    }
                    return 1;
                }
                // Кики закончились -> следующее наказание из правил
                p = pool.stream().filter(x -> !x.type().equals("Кик")).findFirst().orElse(kick);
                msg(s, "§eКики исчерпаны (" + limit + "/" + limit + "), переход к: " + p);
            } else {
                p = pool.get(0);
            }
        } else {
            p = find(pool, wanted);
            if (p == null) {
                msg(s, "§cДля пункта " + id + " нет наказания «" + typeArg + "». Доступно: " + pool);
                return 0;
            }
        }

        if (p.type().equals("Кик")) {
            conn.sendCommand(KICK.formatted(nick, reason));
            counts.merge(key, 1, Integer::sum);
            saveCounts();
            msg(s, "§aКик отправлен");
            return 1;
        }

        String cmd = buildTimed(s, p, nick, id, reason, durArg);
        if (cmd == null) return 0;
        conn.sendCommand(cmd);
        counts.remove(key); // после бана/мута цикл начинается заново
        saveCounts();
        msg(s, "§aОтправлено: /" + cmd);
        return 1;
    }

    private static String nextAfterKick(List<Rules.Punishment> pool) {
        return pool.stream().filter(x -> !x.type().equals("Кик")).findFirst()
                .map(Rules.Punishment::toString).orElse("кик");
    }
}
