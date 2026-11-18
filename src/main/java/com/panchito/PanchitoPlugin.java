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
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class PanchitoPlugin extends JavaPlugin implements Listener {

    private NPCRegistry registry;
    private NPC npc;
    private String mode = "quieto";
    // Objetos que Panchito guardó: dueño (quien los tiró) -> lista de objetos
    private final Map<UUID, List<ItemStack>> guardados = new HashMap<>();
    private final Set<UUID> ignorados = new HashSet<>();
    private Item objetivoItem;
    private final List<ArmorStand> burbujas = new ArrayList<>();
    private BukkitTask tareaBurbuja;
    private int intentos;
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
        Bukkit.getScheduler().runTaskTimer(this, this::loopRecoger, 10L, 10L);
    }

    @Override
    public void onDisable() {
        // Solo limpia lo que creó este plugin (registro propio, no toca el de Citizens)
        Bukkit.getScheduler().cancelTasks(this);
        devolverTodo();
        quitarBurbuja();
        if (npc != null) npc.destroy();
        if (registry != null) registry.deregisterAll();
    }

    private String nombre() {
        return getConfig().getString("nombre", "Panchito");
    }

    private void spawnNpc(Location loc) {
        devolverTodo();
        quitarBurbuja();
        if (npc != null) npc.destroy();
        npc = registry.createNPC(EntityType.PLAYER, nombre());
        npc.getNavigator().getDefaultParameters().range(64f).distanceMargin(2.5);
        net.citizensnpcs.trait.LookClose lc = npc.getOrAddTrait(net.citizensnpcs.trait.LookClose.class); lc.lookClose(true); lc.setRange(8);
        String skin = getConfig().getString("skin", "");
        String textura = getConfig().getString("skin-textura", "");
        String firma = getConfig().getString("skin-firma", "");
        if (textura != null && !textura.isBlank() && firma != null && !firma.isBlank()) {
            // Skin propia (generada en mineskin.org a partir de una imagen o URL)
            npc.getOrAddTrait(net.citizensnpcs.trait.SkinTrait.class)
                    .setSkinPersistent("panchito", firma.trim(), textura.trim());
        } else if (skin != null && !skin.isBlank()) {
            npc.getOrAddTrait(net.citizensnpcs.trait.SkinTrait.class).setSkinName(skin.trim());
        }
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
            // Solo te oye si estás cerca (como si hablaras con él en persona)
            double radio = getConfig().getDouble("radio-hablar", 20);
            Entity ne = npc.getEntity();
            if (!p.getWorld().equals(ne.getWorld())
                    || p.getLocation().distanceSquared(ne.getLocation()) > radio * radio) return;
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
                "vida %.0f/20, modo %s, distancia a %s: %s, mob hostil cercano: %s, objetos guardados de %s: %d",
                vida, mode, p.getName(), dist, h == null ? "ninguno" : h.getType().name(),
                p.getName(), contar(p.getUniqueId()));
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
                + "\"atacar\" (atacar el mob hostil más cercano), \"recoger\" (ir a juntar objetos que los jugadores tiraron; "
                + "los guardas para su dueño), \"dar\" (entregar al jugador que te habla los objetos que guardaste DE ÉL; "
                + "nunca se los das a otro), \"saltar\", \"mirar\" (mirar al jugador), \"ninguna\" (solo hablar).\n"
                + "Juntas solo lo que los jugadores tiran y se lo guardas a quien lo tiró. Solo se lo devuelves a su dueño.";
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

    // Panchito "habla" con una burbuja flotante sobre su cabeza (no sale en el chat).
    // Solo la ven los jugadores que estén cerca de él.
    private void hablar(String texto) {
        getLogger().info("[" + nombre() + "] " + texto);
        if (npc == null || !npc.isSpawned()) return;
        quitarBurbuja();
        List<String> lineas = dividir(texto.replaceAll("\\s+", " ").trim(), 30);
        final int n = lineas.size();
        if (n == 0) return;
        final double[] alturas = new double[n];
        Location base = npc.getEntity().getLocation();
        for (int i = 0; i < n; i++) {
            alturas[i] = 2.0 + (n - 1 - i) * 0.28;
            ArmorStand as = base.getWorld().spawn(base.clone().add(0, alturas[i], 0), ArmorStand.class, a -> {
                a.setInvisible(true);
                a.setMarker(true);
                a.setSmall(true);
                a.setGravity(false);
                a.setSilent(true);
                a.setInvulnerable(true);
                a.setPersistent(false);
                a.setCustomNameVisible(true);
            });
            as.customName(Component.text(lineas.get(i)));
            burbujas.add(as);
        }
        final int duracion = getConfig().getInt("burbuja-segundos", 6) * 10; // ciclos de 2 ticks
        final int[] cuenta = {0};
        tareaBurbuja = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (npc == null || !npc.isSpawned() || ++cuenta[0] > duracion) {
                quitarBurbuja();
                return;
            }
            Location l = npc.getEntity().getLocation();
            for (int i = 0; i < burbujas.size() && i < alturas.length; i++) {
                burbujas.get(i).teleport(l.clone().add(0, alturas[i], 0));
            }
        }, 2L, 2L);
    }

    private void quitarBurbuja() {
        if (tareaBurbuja != null) {
            tareaBurbuja.cancel();
            tareaBurbuja = null;
        }
        for (ArmorStand a : burbujas) a.remove();
        burbujas.clear();
    }

    private List<String> dividir(String t, int ancho) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : t.split(" ")) {
            if (w.isEmpty()) continue;
            if (cur.length() > 0 && cur.length() + 1 + w.length() > ancho) {
                out.add(cur.toString());
                cur = new StringBuilder();
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(w);
        }
        if (cur.length() > 0) out.add(cur.toString());
        if (out.size() > 4) {
            out = new ArrayList<>(out.subList(0, 4));
            out.set(3, out.get(3) + "...");
        }
        return out;
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
                objetivoItem = null;
                npc.getNavigator().cancelNavigation();
            }
            case "atacar" -> {
                LivingEntity m = hostilCercano(24);
                if (m == null) return;
                mode = "atacando";
                objetivoItem = null;
                npc.getNavigator().setTarget(m, true);
            }
            case "recoger" -> {
                mode = "recogiendo";
                objetivoItem = null;
                intentos = 0;
            }
            case "dar" -> {
                if (p != null) dar(p);
            }
            case "saltar" -> ne.setVelocity(ne.getVelocity().add(new Vector(0, 0.45, 0)));
            case "mirar" -> {
                if (p != null) npc.faceLocation(p.getEyeLocation());
            }
            default -> { }
        }
    }

    // ---------- Recoger y devolver objetos ----------
    private int contar(UUID dueno) {
        List<ItemStack> l = guardados.get(dueno);
        if (l == null) return 0;
        int n = 0;
        for (ItemStack st : l) n += st.getAmount();
        return n;
    }

    private Item buscarItem(double r) {
        Entity ne = npc.getEntity();
        int max = getConfig().getInt("max-objetos", 36);
        Item best = null;
        double bd = Double.MAX_VALUE;
        for (Entity en : ne.getNearbyEntities(r, r, r)) {
            if (en instanceof Item it && it.getThrower() != null && it.getPickupDelay() <= 0
                    && !ignorados.contains(it.getUniqueId())) {
                List<ItemStack> l = guardados.get(it.getThrower());
                if (l != null && l.size() >= max) continue;
                double d = it.getLocation().distanceSquared(ne.getLocation());
                if (d < bd) { bd = d; best = it; }
            }
        }
        return best;
    }

    private void loopRecoger() {
        if (npc == null || !npc.isSpawned()) return;
        boolean auto = getConfig().getBoolean("recoger-automatico", true);
        if (!(mode.equals("recogiendo") || (auto && mode.equals("quieto")))) {
            objetivoItem = null;
            return;
        }
        if (ignorados.size() > 200) ignorados.clear();
        Entity ne = npc.getEntity();
        if (objetivoItem != null && !objetivoItem.isValid()) objetivoItem = null;
        if (objetivoItem == null) {
            objetivoItem = buscarItem(getConfig().getDouble("radio-recoger", 12));
            intentos = 0;
            if (objetivoItem == null) {
                if (mode.equals("recogiendo")) mode = "quieto";
                return;
            }
            mode = "recogiendo";
        }
        if (!objetivoItem.getWorld().equals(ne.getWorld())) { objetivoItem = null; return; }
        if (ne.getLocation().distanceSquared(objetivoItem.getLocation()) <= 2.25) {
            guardar(objetivoItem);
            objetivoItem = null;
            mode = "quieto";
            npc.getNavigator().cancelNavigation();
            return;
        }
        if (++intentos > 40) { // 20 segundos sin poder llegar: lo deja
            ignorados.add(objetivoItem.getUniqueId());
            objetivoItem = null;
            mode = "quieto";
            npc.getNavigator().cancelNavigation();
            return;
        }
        npc.getNavigator().setTarget(objetivoItem.getLocation());
    }

    private void guardar(Item it) {
        UUID dueno = it.getThrower();
        if (dueno == null) return;
        guardados.computeIfAbsent(dueno, k -> new ArrayList<>()).add(it.getItemStack().clone());
        Entity ne = npc.getEntity();
        ne.getWorld().playSound(ne.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1f);
        it.remove();
    }

    // Solo entrega al jugador que pide, y solo lo que ÉL tiró
    private void dar(Player p) {
        List<ItemStack> lista = guardados.get(p.getUniqueId());
        if (lista == null || lista.isEmpty()) return;
        Entity ne = npc.getEntity();
        if (!p.getWorld().equals(ne.getWorld()) || p.getLocation().distanceSquared(ne.getLocation()) > 400) {
            hablar("Estás muy lejos, acércate y me lo pides otra vez.");
            return;
        }
        List<ItemStack> sobrantes = new ArrayList<>();
        for (ItemStack st : lista) sobrantes.addAll(p.getInventory().addItem(st).values());
        if (sobrantes.isEmpty()) {
            guardados.remove(p.getUniqueId());
        } else {
            guardados.put(p.getUniqueId(), sobrantes);
            hablar("Tu inventario está lleno, te dejo lo que sobró guardado.");
        }
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1f);
    }

    // Al apagar o quitar a Panchito, nada se pierde: se devuelve o se deja en el suelo
    private void devolverTodo() {
        if (npc == null || !npc.isSpawned()) { guardados.clear(); return; }
        Location donde = npc.getEntity().getLocation();
        for (Map.Entry<UUID, List<ItemStack>> en : guardados.entrySet()) {
            Player dueno = Bukkit.getPlayer(en.getKey());
            for (ItemStack st : en.getValue()) {
                if (dueno != null && dueno.isOnline()) {
                    for (ItemStack resto : dueno.getInventory().addItem(st).values())
                        donde.getWorld().dropItemNaturally(donde, resto);
                } else {
                    donde.getWorld().dropItemNaturally(donde, st);
                }
            }
        }
        guardados.clear();
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
                devolverTodo();
                quitarBurbuja();
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
