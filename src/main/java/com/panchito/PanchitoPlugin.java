package com.panchito;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class PanchitoPlugin extends JavaPlugin implements Listener {

    private NPCRegistry registry;
    private NPC npc;
    private String mode = "quieto";
    private final Deque<JsonObject> history = new ArrayDeque<>(); // solo hilo principal
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        registry = CitizensAPI.createAnonymousNPCRegistry(new MemoryNPCDataStore());
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Location l = Bukkit.getWorlds().get(0).getSpawnLocation();
            spawnNpc(l);
        }, 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::loopDefensa, 20L, 20L);
    }

    @Override
    public void onDisable() {
        // Solo limpia lo que creó este plugin (registro propio, no toca el de Citizens)
        Bukkit.getScheduler().cancelTasks(this);
        if (npc != null) npc.destroy();
        if (registry != null) registry.deregisterAll();
    }

    private String nombre() {
        return getConfig().getString("nombre", "Panchito");
    }

    private void spawnNpc(Location loc) {
        if (npc != null) npc.destroy();
        npc = registry.createNPC(EntityType.PLAYER, nombre());
        npc.getNavigator().getDefaultParameters().range(64f).distanceMargin(2.5);
        npc.spawn(loc);
        mode = "quieto";
    }

    // ---------- Chat (Java y Bedrock via Geyser llegan igual) ----------
    // MONITOR + ignoreCancelled: Panchito solo "escucha", nunca modifica ni cancela
    // el chat, y respeta a otros plugins (mute, filtros, etc.).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
        if (!msg.toLowerCase(Locale.ROOT).contains(nombre().toLowerCase(Locale.ROOT))) return;
        Player p = e.getPlayer();
        UUID uid = p.getUniqueId();
        String user = p.getName();
        Bukkit.getScheduler().runTask(this, () -> {
            if (npc == null || !npc.isSpawned()) return;
            if (!busy.compareAndSet(false, true)) return;
            preguntar(user, msg, estado(p), uid);
        });
    }

    private String estado(Player p) {
        Entity ne = npc.getEntity();
        String dist = ne.getWorld().equals(p.getWorld())
                ? Math.round(ne.getLocation().distance(p.getLocation())) + " bloques"
                : "otro mundo";
        LivingEntity h = hostilCercano(16);
        double vida = ne instanceof LivingEntity le ? le.getHealth() : 20;
        return String.format(Locale.ROOT,
                "vida %.0f/20, modo %s, distancia a %s: %s, mob hostil cercano: %s",
                vida, mode, p.getName(), dist, h == null ? "ninguno" : h.getType().name());
    }

    private LivingEntity hostilCercano(double r) {
        Entity ne = npc.getEntity();
        LivingEntity best = null;
        double bd = r * r;
        for (Entity en : ne.getNearbyEntities(r, r, r)) {
            if (en instanceof Monster m && !m.isDead()) {
                double d = en.getLocation().distanceSquared(ne.getLocation());
                if (d < bd) { bd = d; best = m; }
            }
        }
        return best;
    }

    // ---------- Groq ----------
    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private String key() {
        String env = System.getenv("GROQ_API_KEY");
        return (env != null && !env.isBlank()) ? env : getConfig().getString("groq-key", "");
    }

    private String systemPrompt() {
        String base = getConfig().getString("personalidad", "").replace("{nombre}", nombre());
        return base + "\nDecides tú mismo qué hacer según lo que se te dice y el estado actual.\n"
                + "Responde SIEMPRE y SOLO con un JSON así: {\"say\":\"texto o vacío\",\"action\":\"accion\"}\n"
                + "Acciones: \"seguir\" (seguir al jugador que te habla), \"quieto\" (detenerte), "
                + "\"atacar\" (atacar el mob hostil más cercano), \"recoger\" (ir al objeto más cercano del suelo), "
                + "\"saltar\", \"mirar\" (mirar al jugador), \"ninguna\" (solo hablar).";
    }

    private void preguntar(String user, String texto, String estado, UUID uid) {
        history.addLast(msg("user", "[ESTADO: " + estado + "]\n" + user + ": " + texto));
        while (history.size() > 16) history.removeFirst();

        JsonArray arr = new JsonArray();
        arr.add(msg("system", systemPrompt()));
        for (JsonObject h : history) arr.add(h);

        JsonObject body = new JsonObject();
        body.addProperty("model", getConfig().getString("modelo", "llama-3.3-70b-versatile"));
        body.add("messages", arr);
        JsonObject rf = new JsonObject();
        rf.addProperty("type", "json_object");
        body.add("response_format", rf);
        body.addProperty("max_tokens", 200);
        body.addProperty("temperature", 0.9);

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
            busy.set(false);
            if (err != null || res.statusCode() != 200) {
                getLogger().warning("Groq falló: " + (err != null ? err.getMessage() : res.statusCode() + " " + res.body()));
                Bukkit.getScheduler().runTask(this, () -> hablar("Uy, me falló la IA."));
                return;
            }
            String raw;
            try {
                raw = JsonParser.parseString(res.body()).getAsJsonObject()
                        .getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message").get("content").getAsString().trim();
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(this, () -> hablar("Uy, no entendí a la IA."));
                return;
            }
            final String respuesta = raw;
            Bukkit.getScheduler().runTask(this, () -> {
                history.addLast(msg("assistant", respuesta));
                String say = "", action = "ninguna";
                try {
                    JsonObject j = JsonParser.parseString(respuesta).getAsJsonObject();
                    if (j.has("say") && !j.get("say").isJsonNull()) say = j.get("say").getAsString();
                    if (j.has("action") && !j.get("action").isJsonNull()) action = j.get("action").getAsString();
                } catch (Exception ex) {
                    say = respuesta.length() > 150 ? respuesta.substring(0, 150) : respuesta;
                }
                if (!say.isBlank()) hablar(say);
                ejecutar(action.toLowerCase(Locale.ROOT), Bukkit.getPlayer(uid));
            });
        });
    }

    private void hablar(String texto) {
        Bukkit.broadcast(Component.text("<" + nombre() + "> " + texto));
    }

    // ---------- Acciones ----------
    private void ejecutar(String accion, Player p) {
        if (npc == null || !npc.isSpawned()) return;
        Entity ne = npc.getEntity();
        switch (accion) {
            case "seguir" -> {
                if (p == null || !p.isOnline()) return;
                if (!p.getWorld().equals(ne.getWorld())) npc.teleport(p.getLocation(), TeleportCause.PLUGIN);
                mode = "siguiendo";
                npc.getNavigator().setTarget(p, false);
            }
            case "quieto" -> {
                mode = "quieto";
                npc.getNavigator().cancelNavigation();
            }
            case "atacar" -> {
                LivingEntity m = hostilCercano(24);
                if (m == null) return;
                mode = "atacando";
                npc.getNavigator().setTarget(m, true);
            }
            case "recoger" -> {
                Item best = null;
                double bd = Double.MAX_VALUE;
                for (Entity en : ne.getNearbyEntities(16, 16, 16)) {
                    if (en instanceof Item it) {
                        double d = it.getLocation().distanceSquared(ne.getLocation());
                        if (d < bd) { bd = d; best = it; }
                    }
                }
                if (best == null) return;
                mode = "recogiendo";
                npc.getNavigator().setTarget(best.getLocation());
            }
            case "saltar" -> ne.setVelocity(ne.getVelocity().add(new Vector(0, 0.45, 0)));
            case "mirar" -> {
                if (p != null) npc.faceLocation(p.getEyeLocation());
            }
            default -> { }
        }
    }

    private void loopDefensa() {
        if (npc == null || !npc.isSpawned() || !getConfig().getBoolean("auto-defensa", true)) return;
        if (mode.equals("atacando")) {
            if (!npc.getNavigator().isNavigating()) mode = "quieto";
            return;
        }
        if (hostilCercano(6) != null) ejecutar("atacar", null);
    }

    // ---------- Comandos ----------
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("Uso: /panchito <spawn|remove|reload>");
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> {
                if (s instanceof Player p) {
                    spawnNpc(p.getLocation());
                    s.sendMessage(nombre() + " apareció.");
                } else s.sendMessage("Solo un jugador puede usar esto.");
            }
            case "remove" -> {
                if (npc != null) npc.destroy();
                npc = null;
                s.sendMessage(nombre() + " eliminado.");
            }
            case "reload" -> {
                reloadConfig();
                s.sendMessage("Configuración recargada.");
            }
            default -> s.sendMessage("Uso: /panchito <spawn|remove|reload>");
        }
        return true;
    }
}
