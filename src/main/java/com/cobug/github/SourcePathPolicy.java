package com.cobug.github;

import java.util.*;

/**
 * Defines what COBUG considers project-controlled source/assets when creating
 * a Main snapshot. Runtime environments, build outputs and IDE state are
 * deliberately excluded; tracked project assets such as ML models are kept.
 */
public final class SourcePathPolicy {
    private SourcePathPolicy() {}

    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".svn", ".hg", "node_modules", "dist", "build", "target",
            ".next", ".nuxt", ".cache", ".gradle", ".idea", ".vscode",
            "__pycache__", ".pytest_cache", ".venv", "venv", "coverage", "out",
            "bin", "obj", ".terraform", ".mypy_cache", ".ruff_cache", ".tox",
            "vendor", "Pods", ".parcel-cache", ".turbo", "storybook-static"
    );

    private static final Set<String> IGNORED_EXACT_FILES = Set.of(
            ".ds_store", "thumbs.db", "desktop.ini"
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java","py","js","jsx","ts","tsx","c","h","cc","cpp","hpp","cs",
            "go","rs","kt","kts","swift","rb","php","scala","sh","bash","zsh",
            "fish","bat","cmd","ps1","html","htm","css","scss","sass","less",
            "json","xml","yaml","yml","toml","properties","ini","cfg","conf","md",
            "txt","sql","graphql","gql","csv","env","example","lock","gradle","mvn",
            "dockerfile","gitignore","gitattributes"
    );

    public static boolean isIgnored(String path) {
        String normalized = normalize(path);
        if (normalized.isBlank()) return true;
        String[] parts = normalized.split("/");
        for (String part : parts) {
            if (IGNORED_DIRECTORIES.contains(part)) return true;
        }
        String name = parts[parts.length - 1].toLowerCase(Locale.ROOT);
        if (IGNORED_EXACT_FILES.contains(name)) return true;

        // Environment secrets/runtime files. Keep explicit examples such as
        // .env.example and .env.template because they are source/config docs.
        if (name.equals(".env") || name.startsWith(".env.") && !name.endsWith(".example") && !name.endsWith(".template")) return true;
        if (name.endsWith(".log") || name.endsWith(".tmp") || name.endsWith(".swp") || name.endsWith(".pyc")) return true;
        return false;
    }

    public static boolean looksText(String path, byte[] bytes) {
        String name = normalize(path).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1))) return true;
        if (name.endsWith("/dockerfile") || name.equals("dockerfile") || name.endsWith("/makefile") || name.equals("makefile")) return true;
        if (bytes == null || bytes.length == 0) return true;
        int sample = Math.min(bytes.length, 8192);
        for (int i = 0; i < sample; i++) if (bytes[i] == 0) return false;
        return true;
    }

    public static String normalize(String path) {
        if (path == null) return "";
        return path.replace('\\','/').replaceAll("^/+", "");
    }
}
