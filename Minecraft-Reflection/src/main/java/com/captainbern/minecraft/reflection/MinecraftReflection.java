package com.captainbern.minecraft.reflection;

import com.captainbern.minecraft.reflection.providers.StandardReflectionProvider;
import com.captainbern.minecraft.reflection.providers.remapper.RemappedReflectionProvider;
import com.captainbern.minecraft.reflection.providers.remapper.RemapperException;
import com.captainbern.minecraft.reflection.utils.ClassCache;
import com.captainbern.reflection.Reflection;
import com.captainbern.reflection.provider.ReflectionProvider;
import com.google.common.base.Strings;
import org.bukkit.Bukkit;
import org.bukkit.Server;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * To understand how this all works, please refer to the bottom of the file.
 *
 * This class should provide a pretty safe way of using Reflection with both NMS and CraftBukkit classes.
 * It can never be 100% safe of course but it may be a better alternative compared to all those other
 * "ReflectionUtils" out there.
 */
public class MinecraftReflection {

    /**
     * The CraftBukkit class-cache
     */
    private static ClassCache CRAFTBUKKIT_REFLECTION;

    /**
     * The Minecraft class-cache
     */
    private static ClassCache MINECRAFT_REFLECTION;

    /**
     * The Craftbukkit package
     */
    private static String CRAFTBUKKIT_PACKAGE;

    /**
     * The Minecraft package
     */
    private static String MINECRAFT_PACKAGE;

    /**
     * The Minecraft package-prefix
     */
    private static String MINECRAFT_PACKAGE_PREFIX = "net.minecraft.server";

    /**
     * The MinecraftForge Entity-package
     */
    private static String FORGE_ENTITY_PACKAGE = "net.minecraft.entity";

    /**
     * The Version tag
     */
    private static String VERSION_TAG;

    /**
     * Are we initializing or not?
     */
    private static boolean initializing = false;

    /**
     * Pattern
     */
    private static final Pattern PACKAGE_VERSION_MATCHER = Pattern.compile(".*\\.(v\\d+_\\d+_\\w*\\d+)");

    private MinecraftReflection() {
        super();
    }

    /**
     * Returns the Version tag, used by the safeguard
     * @return The version tag used by the safeguard.
     */
    public static String getVersionTag() {
        if (VERSION_TAG == null)
            initializePackages();
        return VERSION_TAG;
    }

    /**
     * Initializes both the craftbukkit and minecraft packages.
     */
    protected static void initializePackages() {
        if (initializing)
            throw new IllegalStateException("Already initializing the packages!");

        initializing = true;
        Server craftServer = Bukkit.getServer();

        if (craftServer != null) {
            try {

                Class<?> craftServerClass = craftServer.getClass();
                CRAFTBUKKIT_PACKAGE = getPackage(craftServerClass);

                Matcher packageMatcher = PACKAGE_VERSION_MATCHER.matcher(CRAFTBUKKIT_PACKAGE);
                if (packageMatcher.matches()) {
                    VERSION_TAG = packageMatcher.group(1);
                } else {
                    // This is more of a backup
                    MinecraftVersion version = new MinecraftVersion(Bukkit.getVersion());
                    VERSION_TAG = version.toSafeguardTag();
                }

                handlePossiblePackageTrouble();

                Class<?> craftEntityClass = getCraftEntityClass();
                Method getHandle = craftEntityClass.getDeclaredMethod("getHandle");

                MINECRAFT_PACKAGE = getPackage(getHandle.getReturnType());

                if (!MINECRAFT_PACKAGE.startsWith(MINECRAFT_PACKAGE_PREFIX)) {
                    if (MINECRAFT_PACKAGE.equals(FORGE_ENTITY_PACKAGE)) {
                        MINECRAFT_PACKAGE = combine(MINECRAFT_PACKAGE_PREFIX, VERSION_TAG);
                    } else {
                        MINECRAFT_PACKAGE_PREFIX = MINECRAFT_PACKAGE;
                    }
                }

            } catch (SecurityException e) {
                throw new RuntimeException("SEX violation. Cannot get handle method.", e);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Cannot find getHandle() method on server. Is this a modified CraftBukkit version?", e);
            } finally {
                initializing = false;
            }

        } else {
            initializing = false;
            throw new IllegalStateException("It appears Bukkit isn't running properly?");
        }
    }

    /**
     * Returns the last part of the package of a given string, in this case it *should* return the version tag
     * used by the safeguard
     * @param clazz
     * @return
     */
    private static String getPackage(final Class<?> clazz) {
        String fullName = clazz.getCanonicalName();
        int index = fullName.lastIndexOf(".");

        if (index > 0)
            return fullName.substring(0, index);
        else
            return ""; // Default package
    }

    /**
     * "Combines" 2 packages, keeping in mind if one of both packages is NULL.
     * @param part1
     * @param part2
     * @return
     */
    private static String combine(final String part1, final String part2) {
        if (Strings.isNullOrEmpty(part1)) {
            return part2;
        } else if (Strings.isNullOrEmpty(part2)) {
            return part1;
        }

        return part1 + "." + part2;
    }

    /**
     * Handles possible package trouble for specific server mods.
     */
    private static void handlePossiblePackageTrouble() {
        try {
            getCraftEntityClass();
        } catch (Exception e) {
            CRAFTBUKKIT_REFLECTION = null; // We have to nullify this so our changes take effect.
            CRAFTBUKKIT_PACKAGE = "org.bukkit.craftbukkit";

            getCraftEntityClass();
        }
    }

    /**
     * Returns the (versioned) CraftBukkit package.
     * @return
     */
    public static String getCraftBukkitPackage() {
        if (CRAFTBUKKIT_PACKAGE == null)
            initializePackages();

        return CRAFTBUKKIT_PACKAGE;
    }

    /**
     * Returns the (versioned) Minecraft package.
     * @return
     */
    public static String getMinecraftPackage() {
        if (MINECRAFT_PACKAGE == null)
            initializePackages();

        return MINECRAFT_PACKAGE;
    }

    /**
     * Returns a class with the given name, using the context ClassLoader.
     * @param className
     * @return
     * @throws ClassNotFoundException
     */
    public static Class<?> getClass(final String className) throws ClassNotFoundException {
        try {
            return Thread.currentThread().getContextClassLoader().loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundException("Failed to find class: " + className);
        }
    }

    /**
     * Returns a CraftBukkit class with the given name.
     * @param className
     * @return
     */
    public static Class<?> getCraftBukkitClass(final String className) {
        if (CRAFTBUKKIT_REFLECTION == null) {
            ReflectionConfiguration configuration = new ReflectionConfiguration.Builder()
                    .withClassLoader(MinecraftReflection.class.getClassLoader())
                    .withPackagePrefix(getCraftBukkitPackage())
                    .build();

            ReflectionProvider provider = null;

            try {
                provider = new RemappedReflectionProvider(configuration);
            } catch (RemapperException e) {
                switch (e.getReason()) {
                    case REMAPPER_DISABLED:
                        System.out.println("It appears you are running MCPC+ but the remapper is disabled, please enable it.");
                        break;
                    default:
                        provider = new StandardReflectionProvider(configuration);
                }
            }

            CRAFTBUKKIT_REFLECTION = new ClassCache(new Reflection(provider));
        }

        return CRAFTBUKKIT_REFLECTION.getClass(className);
    }

    /**
     * Returns a Minecraft class with the given name.
     * @param className
     * @return
     */
    public static Class<?> getMinecraftClass(final String className) {
        if (MINECRAFT_REFLECTION == null) {
            ReflectionConfiguration configuration = new ReflectionConfiguration.Builder()
                    .withClassLoader(MinecraftReflection.class.getClassLoader())
                    .withPackagePrefix(getMinecraftPackage())
                    .build();

            ReflectionProvider provider = null;

            try {
                provider = new RemappedReflectionProvider(configuration);
            } catch (RemapperException e) {
                switch (e.getReason()) {
                    case REMAPPER_DISABLED:
                        System.out.println("It appears you are running MCPC+ but the remapper is disabled, please enable it.");
                        break;
                    default:
                        provider = new StandardReflectionProvider(configuration);
                }
            }

            MINECRAFT_REFLECTION = new ClassCache(new Reflection(provider));
        }

        return MINECRAFT_REFLECTION.getClass(className);
    }

    /**
     * Useful methods
     */

    public static Class<?> getCraftEntityClass() {
        return getCraftBukkitClass("entity.CraftEntity");
    }
}

/**
 * No.
 */