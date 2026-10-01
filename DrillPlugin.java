package dev.drill;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class DrillPlugin extends JavaPlugin implements Listener {

    private NamespacedKey drillKey;
    private NamespacedKey recipeKey;
    private final Set<UUID> busy = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        drillKey = new NamespacedKey(this, "drill");
        recipeKey = new NamespacedKey(this, "drill_recipe");

        registerRecipe();
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        getServer().removeRecipe(recipeKey);
    }

    // ---------------------------------------------------------------
    // Item
    // ---------------------------------------------------------------

    /** Netherite pickaxe base = netherite durability, speed, enchantability and fire/lava immunity. */
    public ItemStack createDrill() {
        ItemStack item = new ItemStack(Material.NETHERITE_PICKAXE);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("Drill", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Mines a wide area at once.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Sneak to mine a single block.", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.getPersistentDataContainer().set(drillKey, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }

    public boolean isDrill(ItemStack item) {
        if (item == null || item.getType() != Material.NETHERITE_PICKAXE || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(drillKey, PersistentDataType.BYTE);
    }

    // ---------------------------------------------------------------
    // Recipe
    //   N N N     N = Netherite Ingot
    //   . B .     B = Blaze Rod
    //   . B .
    // ---------------------------------------------------------------

    private void registerRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createDrill());
        recipe.shape("NNN", " B ", " B ");
        recipe.setIngredient('N', Material.NETHERITE_INGOT);
        recipe.setIngredient('B', Material.BLAZE_ROD);
        getServer().addRecipe(recipe);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        Recipe r = e.getRecipe();
        if (!(r instanceof ShapedRecipe sr) || !sr.getKey().equals(recipeKey)) return;
        CraftingInventory inv = e.getInventory();
        if (!e.getViewers().isEmpty() && !e.getViewers().get(0).hasPermission("drill.craft")) {
            inv.setResult(null);
        }
    }

    // ---------------------------------------------------------------
    // Area mining
    // ---------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (busy.contains(p.getUniqueId())) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (!p.hasPermission("drill.use")) return;
        if (getConfig().getBoolean("sneak-disables", true) && p.isSneaking()) return;

        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!isDrill(tool)) return;

        Block origin = e.getBlock();
        RayTraceResult ray = p.rayTraceBlocks(7);
        if (ray == null || ray.getHitBlock() == null || !ray.getHitBlock().equals(origin)) return;
        BlockFace face = ray.getHitBlockFace();
        if (face == null) return;

        int r = Math.max(1, getConfig().getInt("radius", 1));

        busy.add(p.getUniqueId());
        try {
            for (int a = -r; a <= r; a++) {
                for (int b = -r; b <= r; b++) {
                    if (a == 0 && b == 0) continue;

                    int dx, dy, dz;
                    if (face.getModY() != 0) { dx = a; dy = 0; dz = b; }
                    else if (face.getModX() != 0) { dx = 0; dy = a; dz = b; }
                    else { dx = a; dy = b; dz = 0; }

                    Block target = origin.getRelative(dx, dy, dz);
                    Material m = target.getType();
                    if (m.isAir() || target.isLiquid()) continue;
                    if (m.getHardness() < 0) continue;                 // bedrock, barriers, etc.
                    if (!Tag.MINEABLE_PICKAXE.isTagged(m)) continue;   // only pickaxe blocks

                    if (!isDrill(p.getInventory().getItemInMainHand())) return; // drill broke
                    p.breakBlock(target); // fires BlockBreakEvent (works with protection plugins), drops + durability
                }
            }
        } finally {
            busy.remove(p.getUniqueId());
        }
    }

    // ---------------------------------------------------------------
    // Command: /drill give [player]
    // ---------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(Component.text("Usage: /drill give [player]", NamedTextColor.RED));
            return true;
        }
        if (!sender.hasPermission("drill.give")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }

        Player target;
        if (args.length >= 2) {
            target = getServer().getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(Component.text("Specify a player.", NamedTextColor.RED));
            return true;
        }

        target.getInventory().addItem(createDrill())
                .values().forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        sender.sendMessage(Component.text("Gave a Drill to " + target.getName() + ".", NamedTextColor.GREEN));
        return true;
    }
}
