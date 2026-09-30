package ru.prime.anticheat.violation;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;

/**
 * Sender for console punishments with a configurable name (colors + %prefix%).
 * Everything except getName() is delegated to the real console sender,
 * so permissions/server/attachments behave like the console.
 */
public class PunishmentSender implements CommandSender {

    public final CommandSender console;
    public final String name;

    public PunishmentSender(CommandSender console, String name) {
        this.console = console;
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void sendMessage(String message) {
        console.sendMessage(message);
    }

    @Override
    public void sendMessage(String[] messages) {
        console.sendMessage(messages);
    }

    @Override
    public void sendMessage(UUID sender, String message) {
        console.sendMessage(sender, message);
    }

    @Override
    public void sendMessage(UUID sender, String[] messages) {
        console.sendMessage(sender, messages);
    }

    @Override
    public Server getServer() {
        return console.getServer();
    }

    @Override
    public CommandSender.Spigot spigot() {
        return console.spigot();
    }

    @Override
    public boolean isPermissionSet(String name) {
        return console.isPermissionSet(name);
    }

    @Override
    public boolean isPermissionSet(Permission perm) {
        return console.isPermissionSet(perm);
    }

    @Override
    public boolean hasPermission(String name) {
        return console.hasPermission(name);
    }

    @Override
    public boolean hasPermission(Permission perm) {
        return console.hasPermission(perm);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
        return console.addAttachment(plugin, name, value);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin) {
        return console.addAttachment(plugin);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) {
        return console.addAttachment(plugin, name, value, ticks);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
        return console.addAttachment(plugin, ticks);
    }

    @Override
    public void removeAttachment(PermissionAttachment attachment) {
        console.removeAttachment(attachment);
    }

    @Override
    public void recalculatePermissions() {
        console.recalculatePermissions();
    }

    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        return console.getEffectivePermissions();
    }

    @Override
    public boolean isOp() {
        return console.isOp();
    }

    @Override
    public void setOp(boolean value) {
        console.setOp(value);
    }
}
