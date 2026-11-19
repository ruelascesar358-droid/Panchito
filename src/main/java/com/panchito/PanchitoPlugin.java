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
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.EquipmentSlot;
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
import java.util.Collection;
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
    private String mode = "libre";
    private boolean quietoFijo;       // true = alguien le ordenó quedarse quieto en su lugar
    private Location casa;             // punto alrededor del cual pasea
    private boolean caminando;
    private int pausaLibre;
    private final java.util.Random azar = new java.util.Random();
    // Objetos que Panchito guardó: dueño (quien los tiró) -> lista de objetos
    private final Map<UUID, List<ItemStack>> guardados = new HashMap<>();
    private final Set<UUID> ignorados = new HashSet<>();
    private Item objetivoItem;
    private boolean entregando;
    private boolean recibiendo;
    // Regalos pendientes: destinatario -> objetos que se le entregarán cuando Panchito lo vea
    private final Map<UUID, List<ItemStack>> regalos = new HashMap<>();
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
        Bukkit.getScheduler().runTaskTimer(this, this::loopRegalos, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::loopLibre, 20L, 20L);
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
        quietoFijo = false;
        mode = "libre";
        casa = loc.clone();
        caminando = false;
        pausaLibre = 0;
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
            if (mode.equals("libre")) { // alguien le habla: se queda quieto un rato
                npc.getNavigator().cancelNavigation();
                caminando = false;
                pausaLibre = 8;
            }
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
                "vida %.0f/20, modo %s, distancia a %s: %s, mob hostil cercano: %s, objetos guardados de %s: %s, jugadores en línea: %s",
                vida, mode, p.getName(), dist, h == null ? "ninguno" : h.getType().name(),
                p.getName(), resumen(p.getUniqueId()), nombresEnLinea());
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
                + "Responde SIEMPRE y SOLO con un JSON así: {\"say\":\"texto o vacío\",\"action\":\"accion\",\"para\":\"solo si regalas\"}\n"
                + "Acciones: \"seguir\" (seguir al jugador que te habla), \"quieto\" (te quedas fijo en ese lugar, sin pasear, hasta que te digan que puedes moverte), \"libre\" (ya puedes moverte: vuelves a pasear libremente), "
                + "\"atacar\" (atacar el mob hostil más cercano), \"recoger\" (ir a juntar objetos que los jugadores tiraron; "
                + "los guardas para su dueño), \"dar\" (entregar al jugador que te habla los objetos que guardaste DE ÉL; "
                + "nunca se los das a otro), \"regalar\" (el jugador que te habla te pide dar SUS objetos guardados a otro jugador: pon en \"para\" el nombre exacto del otro jugador; se los entregas cuando lo veas cerca), \"saltar\", \"mirar\" (mirar al jugador), \"ninguna\" (solo hablar).\n"
                + "Juntas solo lo que los jugadores tiran y se lo guardas a quien lo tiró. Solo se lo devuelves a su dueño.\n"
                + "Si te preguntan qué tienes guardado de ellos, dilo en español usando el campo \"objetos guardados\" del estado "
                + "(traduce los nombres de los objetos); nunca reveles lo que guardaste de otros jugadores.";
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
                String say = "", action = "ninguna", para = "";
                try {
                    JsonObject j = JsonParser.parseString(respuesta).getAsJsonObject();
                    if (j.has("say") && !j.get("say").isJsonNull()) say = j.get("say").getAsString();
                    if (j.has("action") && !j.get("action").isJsonNull()) action = j.get("action").getAsString();
                    if (j.has("para") && !j.get("para").isJsonNull()) para = j.get("para").getAsString();
                } catch (Exception ex) {
                    say = respuesta.length() > 150 ? respuesta.substring(0, 150) : respuesta;
                }
                if (!say.isBlank()) hablar(say);
                ejecutar(action.toLowerCase(Locale.ROOT), Bukkit.getPlayer(uid), para);
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
        ejecutar(accion, p, "");
    }

    private void ejecutar(String accion, Player p, String para) {
        if (npc == null || !npc.isSpawned()) return;
        Entity ne = npc.getEntity();
        switch (accion) {
            case "seguir" -> {
                if (p == null || !p.isOnline()) return;
                if (!p.getWorld().equals(ne.getWorld())) npc.teleport(p.getLocation(), TeleportCause.PLUGIN);
                mode = "siguiendo";
                caminando = false;
                npc.getNavigator().setTarget(p, false);
            }
            case "quieto" -> {
                mode = "quieto";
                quietoFijo = true;
                objetivoItem = null;
                npc.getNavigator().cancelNavigation();
            }
            case "libre" -> {
                quietoFijo = false;
                mode = "libre";
                objetivoItem = null;
                casa = ne.getLocation().clone();
                caminando = false;
                pausaLibre = 0;
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
            case "regalar" -> {
                if (p != null) regalar(p, para);
            }
            case "saltar" -> ne.setVelocity(ne.getVelocity().add(new Vector(0, 0.45, 0)));
            case "mirar" -> {
                if (p != null) npc.faceLocation(p.getEyeLocation());
            }
            default -> { }
        }
    }

    // ---------- Recoger y devolver objetos ----------
    // Texto con lo que Panchito guardó de un jugador, ej: "64 stone, 3 torch"
    private String resumen(UUID dueno) {
        List<ItemStack> l = guardados.get(dueno);
        if (l == null || l.isEmpty()) return "nada";
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        for (ItemStack st : l) {
            String n = st.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            m.merge(n, st.getAmount(), Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        int c = 0;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (c == 10) { sb.append(", y más"); break; }
            if (c++ > 0) sb.append(", ");
            sb.append(e.getValue()).append(' ').append(e.getKey());
        }
        return sb.toString();
    }

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
        if (npc == null || !npc.isSpawned() || entregando || recibiendo) return;
        boolean auto = getConfig().getBoolean("recoger-automatico", true);
        if (!(mode.equals("recogiendo") || (auto && mode.equals("libre")))) {
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
                if (mode.equals("recogiendo")) mode = modoReposo();
                return;
            }
            mode = "recogiendo";
        }
        if (!objetivoItem.getWorld().equals(ne.getWorld())) { objetivoItem = null; return; }
        if (ne.getLocation().distanceSquared(objetivoItem.getLocation()) <= 2.25) {
            guardar(objetivoItem);
            objetivoItem = null;
            mode = modoReposo();
            npc.getNavigator().cancelNavigation();
            return;
        }
        if (++intentos > 40) { // 20 segundos sin poder llegar: lo deja
            ignorados.add(objetivoItem.getUniqueId());
            objetivoItem = null;
            mode = modoReposo();
            npc.getNavigator().cancelNavigation();
            return;
        }
        npc.getNavigator().setTarget(objetivoItem.getLocation());
    }

    private void guardar(Item it) {
        UUID dueno = it.getThrower();
        if (dueno == null) return;
        guardados.computeIfAbsent(dueno, k -> new ArrayList<>()).add(it.getItemStack().clone());
        ponerEnMano(it.getItemStack());
        Entity ne = npc.getEntity();
        ne.getWorld().playSound(ne.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1f);
        it.remove();
    }

    // Objeto en la mano de Panchito (null = mano vacía)
    private void ponerEnMano(ItemStack st) {
        if (npc == null || !npc.isSpawned()) return;
        net.citizensnpcs.api.trait.trait.Equipment eq =
                npc.getOrAddTrait(net.citizensnpcs.api.trait.trait.Equipment.class);
        eq.set(net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.HAND,
                st == null ? new ItemStack(Material.AIR) : st.clone());
    }

    private String nombresEnLinea() {
        StringBuilder sb = new StringBuilder();
        int c = 0;
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (c++ >= 12) break;
            if (sb.length() > 0) sb.append(", ");
            sb.append(pl.getName());
        }
        return sb.length() == 0 ? "ninguno" : sb.toString();
    }

    private boolean lejos(Player p) {
        Entity ne = npc.getEntity();
        return !p.getWorld().equals(ne.getWorld()) || p.getLocation().distanceSquared(ne.getLocation()) > 400;
    }

    // Solo entrega al jugador que pide, y solo lo que ÉL tiró.
    private void dar(Player p) {
        if (entregando || recibiendo) return;
        final UUID uid = p.getUniqueId();
        List<ItemStack> lista = guardados.get(uid);
        if (lista == null || lista.isEmpty()) return;
        if (lejos(p)) {
            hablar("Estás muy lejos, acércate y me lo pides otra vez.");
            return;
        }
        animarEntrega(p, lista, () -> {
            List<ItemStack> g = guardados.get(uid);
            if (g != null && g.isEmpty()) guardados.remove(uid);
        });
    }

    // Un jugador le pide a Panchito dar SUS objetos a otro jugador (se los da cuando lo vea)
    private void regalar(Player donante, String para) {
        Player dest = null;
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (pl.getName().equalsIgnoreCase(para.trim())) { dest = pl; break; }
        }
        if (dest == null) {
            hablar("No encuentro a " + (para.isBlank() ? "ese jugador" : para) + ".");
            return;
        }
        if (dest.equals(donante)) {
            dar(donante);
            return;
        }
        List<ItemStack> mias = guardados.remove(donante.getUniqueId());
        if (mias == null || mias.isEmpty()) {
            hablar("No tengo nada tuyo para regalar.");
            return;
        }
        regalos.computeIfAbsent(dest.getUniqueId(), k -> new ArrayList<>()).addAll(mias);
    }

    // Cuando Panchito ve al destinatario cerca, le entrega el regalo
    private void loopRegalos() {
        if (npc == null || !npc.isSpawned() || entregando || recibiendo || regalos.isEmpty()) return;
        Entity ne = npc.getEntity();
        for (Map.Entry<UUID, List<ItemStack>> en : new ArrayList<>(regalos.entrySet())) {
            final UUID k = en.getKey();
            Player d = Bukkit.getPlayer(k);
            if (d == null || !d.isOnline() || !d.getWorld().equals(ne.getWorld())) continue;
            if (d.getLocation().distanceSquared(ne.getLocation()) > 100) continue; // 10 bloques
            if (ne instanceof LivingEntity le && !le.hasLineOfSight(d)) continue;
            hablar("¡" + d.getName() + "! Tengo algo para ti.");
            animarEntrega(d, en.getValue(), () -> {
                List<ItemStack> g = regalos.get(k);
                if (g != null && g.isEmpty()) regalos.remove(k);
            });
            return;
        }
    }

    // Cada objeto aparece primero en la mano de Panchito y después se lo da al jugador
    private void animarEntrega(Player destino, List<ItemStack> origen, Runnable alTerminar) {
        entregando = true;
        final UUID uid = destino.getUniqueId();
        final Deque<ItemStack> cola = new ArrayDeque<>(origen);
        final boolean[] enMano = {false};
        final int[] espera = {0};
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            Player dest = Bukkit.getPlayer(uid);
            boolean invalido = npc == null || !npc.isSpawned() || dest == null || !dest.isOnline();
            if (invalido || (!enMano[0] && cola.isEmpty())) {
                task.cancel();
                entregando = false;
                ponerEnMano(null);
                alTerminar.run();
                return;
            }
            if (!enMano[0]) {
                ponerEnMano(cola.peekFirst());
                npc.faceLocation(dest.getEyeLocation());
                enMano[0] = true;
                espera[0] = 0;
                return;
            }
            if (++espera[0] < 2) return; // lo sostiene un momento antes de dárselo
            ItemStack st = cola.pollFirst();
            origen.remove(st);
            Collection<ItemStack> resto = dest.getInventory().addItem(st.clone()).values();
            dest.playSound(dest.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1f);
            enMano[0] = false;
            ponerEnMano(null);
            if (!resto.isEmpty()) {
                origen.addAll(resto);
                cola.clear();
                hablar("Tu inventario está lleno, te dejo lo que sobró guardado.");
            }
        }, 0L, 10L);
    }

    // Un jugador le da un objeto: se agacha + clic derecho sobre Panchito con el objeto en la mano
    @EventHandler(ignoreCancelled = true)
    public void onToca(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (npc == null || !npc.isSpawned()) return;
        if (!e.getRightClicked().getUniqueId().equals(npc.getEntity().getUniqueId())) return;
        Player p = e.getPlayer();
        if (getConfig().getBoolean("entrega-agachado", true) && !p.isSneaking()) return;
        ItemStack mano = p.getInventory().getItemInMainHand();
        if (mano.getType().isAir()) return;
        e.setCancelled(true);
        if (entregando || recibiendo) return;
        List<ItemStack> g = guardados.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        if (g.size() >= getConfig().getInt("max-objetos", 36)) {
            hablar("Ya no me cabe más de lo tuyo.");
            return;
        }
        final ItemStack recibido = mano.clone();
        p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        g.add(recibido.clone()); // queda guardado de inmediato (así nunca se pierde)
        recibiendo = true;
        ponerEnMano(recibido);
        npc.faceLocation(p.getEyeLocation());
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1f);
        hablar("¡Gracias! Lo guardo.");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            recibiendo = false;
            if (!entregando) ponerEnMano(null);
        }, 30L);
    }

    // Al apagar o quitar a Panchito, nada se pierde: se devuelve o se deja en el suelo
    private void devolverTodo() {
        if (npc == null || !npc.isSpawned()) { guardados.clear(); regalos.clear(); return; }
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
        for (List<ItemStack> lst : regalos.values())
            for (ItemStack st : lst) donde.getWorld().dropItemNaturally(donde, st);
        regalos.clear();
        guardados.clear();
        ponerEnMano(null);
    }

    private void loopDefensa() {
        if (npc == null || !npc.isSpawned() || !getConfig().getBoolean("auto-defensa", true)) return;
        if (mode.equals("atacando")) {
            if (!npc.getNavigator().isNavigating()) mode = modoReposo();
            return;
        }
        if (!quietoFijo && hostilCercano(6) != null) ejecutar("atacar", null);
    }

    // ---------- Pasear libremente ----------
    private String modoReposo() {
        return quietoFijo ? "quieto" : "libre";
    }

    private Location buscarSuelo(org.bukkit.World w, int x, int z, int yRef) {
        for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3}) {
            int y = yRef + dy;
            org.bukkit.block.Block pies = w.getBlockAt(x, y, z);
            if (w.getBlockAt(x, y - 1, z).isSolid() && !w.getBlockAt(x, y - 1, z).isLiquid()
                    && pies.isPassable() && !pies.isLiquid() && w.getBlockAt(x, y + 1, z).isPassable()) {
                return new Location(w, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    // Panchito pasea solo; si alguien se acerca se queda quieto, y cuando se aleja sigue paseando
    private void loopLibre() {
        if (npc == null || !npc.isSpawned() || !mode.equals("libre") || entregando || recibiendo) return;
        if (!getConfig().getBoolean("moverse-libre", true)) return;
        Entity ne = npc.getEntity();
        double rq = getConfig().getDouble("radio-quieto", 5);
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (pl.getGameMode() != GameMode.SPECTATOR && pl.getWorld().equals(ne.getWorld())
                    && pl.getLocation().distanceSquared(ne.getLocation()) <= rq * rq) {
                if (npc.getNavigator().isNavigating()) npc.getNavigator().cancelNavigation();
                caminando = false;
                pausaLibre = 2 + azar.nextInt(3);
                return;
            }
        }
        if (caminando) {
            if (npc.getNavigator().isNavigating()) return;
            caminando = false;                    // llegó (o no pudo llegar)
            pausaLibre = 2 + azar.nextInt(5);
            return;
        }
        if (pausaLibre > 0) { pausaLibre--; return; }
        if (casa == null || !casa.getWorld().equals(ne.getWorld())) casa = ne.getLocation().clone();
        double radio = getConfig().getDouble("radio-paseo", 8);
        for (int i = 0; i < 6; i++) {
            double ang = azar.nextDouble() * Math.PI * 2;
            double dist = 2 + azar.nextDouble() * Math.max(1, radio - 2);
            int x = (int) Math.floor(casa.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(casa.getZ() + Math.sin(ang) * dist);
            Location dest = buscarSuelo(ne.getWorld(), x, z, ne.getLocation().getBlockY());
            if (dest != null) {
                npc.getNavigator().setTarget(dest);
                caminando = true;
                return;
            }
        }
        pausaLibre = 3;
    }

    // ---------- Comandos ----------
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("Uso: /panchito <spawn|remove|reload|inventario>");
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
            case "inventario" -> {
                if (guardados.isEmpty()) {
                    s.sendMessage(nombre() + " no tiene nada guardado.");
                } else {
                    for (UUID u : guardados.keySet()) {
                        String nom = Bukkit.getOfflinePlayer(u).getName();
                        s.sendMessage((nom != null ? nom : u.toString()) + ": " + resumen(u));
                    }
                }
            }
            case "reload" -> {
                reloadConfig();
                s.sendMessage("Configuración recargada.");
            }
            default -> s.sendMessage("Uso: /panchito <spawn|remove|reload|inventario>");
        }
        return true;
    }
}
